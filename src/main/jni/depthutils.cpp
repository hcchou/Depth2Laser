#include <jni.h>
#include <android/log.h>
#include <sys/types.h>
#include <assert.h>
#include <stdlib.h>

extern "C"
{

#define LOG_TAG		"DepthUtils"

#define ALOGD(...) __android_log_print (ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define ALOGE(...) __android_log_print (ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define ALOGW(...) __android_log_print (ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

JNIEXPORT jboolean JNICALL Java_com_asus_toby_depth_drawPoints (JNIEnv *env, jobject obj, const jobject pntArrList, jobject laserBuf, jint bufWidth, jint bufHeight, jfloat minWorldX, jfloat maxWorldX, jfloat minWorldY, jfloat maxWorldY)
{
	jclass alCls = env->FindClass("java/util/ArrayList");
	jclass ptCls = env->FindClass("com/intel/camera2/extensions/depthcamera/Point3DF");
	jmethodID alSizeId = env->GetMethodID(alCls, "size", "()I");
	jmethodID alGetId = env->GetMethodID(alCls, "get", "(I)Ljava/lang/Object;");
	jfieldID ptXId = env->GetFieldID(ptCls, "x", "F");
	jfieldID ptYId = env->GetFieldID(ptCls, "y", "F");
	jfieldID ptZId = env->GetFieldID(ptCls, "z", "F");

	float px, pz;

	void *output_buf = env->GetDirectBufferAddress(laserBuf);
	if (!output_buf)
	{
		ALOGE ("%s: Failed getting output_buf", __FUNCTION__);
		return JNI_FALSE;
	}

	unsigned int *poutput = (unsigned int *)output_buf;
	unsigned int *poutput_arr[8];

	// fill black
	for (int row=0; row<bufHeight; row++) {
		for (int col=0; col<bufWidth; col++) {
			*poutput = 0xff000000;
			poutput++;
		}
	}

	int pixelX, pixelY;
	jobject objPoint;
	jint arr_size = env->CallIntMethod(pntArrList, alSizeId);
	for (jsize i=0; i<arr_size; i++) {
		objPoint = env->CallObjectMethod(pntArrList, alGetId, i);
		px = static_cast<float>(env->GetFloatField(objPoint, ptXId));
		pz = static_cast<float>(env->GetFloatField(objPoint, ptZId));
		pixelX =(int)((px - minWorldX) * (float)bufWidth / (float)(maxWorldX - minWorldX));
		pixelY =(int)(bufHeight - (pz * (float)bufHeight / (float)(maxWorldY - minWorldY)));

		poutput = (unsigned int *)(output_buf + 4*(bufWidth * pixelY + pixelX));
		// make points larger
		poutput_arr[0] = (unsigned int *)(output_buf + 4*(bufWidth * (pixelY-1) + (pixelX-1)));
		poutput_arr[1] = (unsigned int *)(output_buf + 4*(bufWidth * (pixelY-1) + pixelX));
		poutput_arr[2] = (unsigned int *)(output_buf + 4*(bufWidth * (pixelY-1) + (pixelX+1)));
		poutput_arr[3] = (unsigned int *)(output_buf + 4*(bufWidth * (pixelY) + (pixelX-1)));
		poutput_arr[4] = (unsigned int *)(output_buf + 4*(bufWidth * (pixelY) + (pixelX+1)));
		poutput_arr[5] = (unsigned int *)(output_buf + 4*(bufWidth * (pixelY+1) + (pixelX-1)));
		poutput_arr[6] = (unsigned int *)(output_buf + 4*(bufWidth * (pixelY+1) + (pixelX)));
		poutput_arr[7] = (unsigned int *)(output_buf + 4*(bufWidth * (pixelY+1) + (pixelX+1)));
		//ALOGD("px=%f pz=%f pixelX=%d pixelY=%d bufWidth=%d poutput=%p", px, pz, pixelX, pixelY, bufWidth, poutput);

		if (!(pixelX==0 && pixelY==0) && ( pixelX < bufWidth && pixelY < bufHeight ) && (pixelX>=0 && pixelY>=0)){
			*poutput = 0xffffffff;
			for(jsize j=0; j<8; j++){
				*(poutput_arr[j]) = 0xffffffff;
			}
		}
	}

	return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL Java_com_asus_toby_depth_depthToGrayscale (JNIEnv *env, jobject obj, const jobject depthBuf, jobject grayscaleBuf, jint bufSize)
{
	void *input_buf = env->GetDirectBufferAddress(depthBuf);
	if (!input_buf)
	{
		ALOGE ("%s: Failed getting input_buf", __FUNCTION__);
		return JNI_FALSE;
	}

	void *output_buf = env->GetDirectBufferAddress(grayscaleBuf);
	if (!input_buf)
	{
		ALOGE ("%s: Failed getting output_buf", __FUNCTION__);
		return JNI_FALSE;
	}

	unsigned short *pinput = (unsigned short *)input_buf;
	unsigned int *poutput = (unsigned int *)output_buf;
	unsigned short depth;
	int color;

	for (int i = 0; i < bufSize; i++)
	{
		depth = *pinput;
		if (depth == 0) {
			// No depth info for this pixel
			*poutput = 0xff000000;
		} else {
			color = 0xff - ((depth >> 4) & 0xff);
			*poutput = 0xff000000 | (color << 16) | (color << 8) | color;
		}
		pinput++;
		poutput++;
	}

	return JNI_TRUE;
}


JNIEXPORT jboolean JNICALL Java_com_asus_toby_depth_uvMapToRGB (JNIEnv *env, jobject obj, const jobject depthBufSrc, const jobject uvMapSrc, const jobject colorPixelsSrc, jobject dst, int depthWidth, int depthHeight, int colorWidth, int colorHeight)
{
    uint16_t *depthBuf = (unsigned short *)env->GetDirectBufferAddress(depthBufSrc);
    unsigned int *uvMapRGB = (unsigned int *)env->GetDirectBufferAddress(dst);
    unsigned int *colorPixels = (unsigned int *)env->GetDirectBufferAddress(colorPixelsSrc);
    float* uvMap = (float*) env->GetDirectBufferAddress(uvMapSrc);
    int color;
    int x, y, i;
    int idx = 0;
    int uvMapIdx = 0;    

	if (depthWidth <= 0 ||  depthHeight <= 0)
	{
		ALOGW("%s: depth buffer sizes is not valid: w %d h %d", __FUNCTION__, depthWidth, depthHeight);
		return JNI_FALSE;
	}
	if (colorWidth <= 0 ||  colorHeight <= 0)
	{
		ALOGW("%s: color buffer sizes is not valid: w %d h %d", __FUNCTION__, colorWidth, colorHeight);
		return JNI_FALSE;
	}
	if (colorPixels == NULL)
	{
		ALOGW("%s: Can't convert colorPixelsSrc direct buffer address", __FUNCTION__);
		return JNI_FALSE;
	}
	if (uvMap == NULL)
	{
		ALOGW("%s: Can't convert uvMapSrc direct buffer address", __FUNCTION__);
		return JNI_FALSE;
	}
	if (depthBuf == NULL)
	{
		ALOGW("%s: Can't convert depthBuffV direct buffer address", __FUNCTION__);
		return JNI_FALSE;
	}
	if (uvMapRGB == NULL)
	{
		ALOGW("%s: Can't convert dst direct buffer address", __FUNCTION__);
		return JNI_FALSE;
	}

   	for ( int row = 0; row < depthHeight ; row++)
	{
		for ( int col = 0; col < depthWidth; col++)
		{
			x = (int) uvMap[uvMapIdx++];
			y = (int) uvMap[uvMapIdx++];
			
			if (!(x==0 && y==0) && ( x < colorWidth && y < colorHeight ) && (x>=0 && y>=0))
			{			
				color = colorPixels[(colorWidth*y + x)];				
				uvMapRGB[idx]  = color;
			}
			else
			{
			 	uvMapRGB[idx]  = 0xFF000000;
			}			
            idx++;
		}		
	}

	return JNI_TRUE;
}

static JNINativeMethod methodTable[] = {
		//{"drawPoints","(Ljava/util/ArrayList;Ljava/nio/ByteBuffer;IIFFFF)Z", (void *) Java_com_asus_toby_depth_drawPoints},
		{"drawPoints", "(Ljava/util/ArrayList;Ljava/nio/ByteBuffer;IIFFFF)Z", (void *) Java_com_asus_toby_depth_drawPoints},
		{"depthToGrayscale", "(Ljava/nio/ByteBuffer;Ljava/nio/ByteBuffer;I)Z", (void *) Java_com_asus_toby_depth_depthToGrayscale},
	{"uvMapToRGB", "(Ljava/nio/ByteBuffer;Ljava/nio/ByteBuffer;Ljava/nio/ByteBuffer;Ljava/nio/ByteBuffer;IIII)Z", (void *) Java_com_asus_toby_depth_uvMapToRGB},
};

const char* activityClassPath = "com/asus/toby/depth/DepthImage2LaserActivity";

jint JNI_OnLoad(JavaVM* aVm, void* aReserved)
{
	JNIEnv* env;
	if (aVm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK)
	{
		ALOGE ("Failed to get JNI_1_6 environment");
		return -1;
	}

	jclass activityClass = env->FindClass(activityClassPath);
	if (!activityClass)
	{
		ALOGE("failed to get %s class reference", activityClassPath);
		return -1;
	}

	env->RegisterNatives(activityClass, methodTable, sizeof(methodTable) / sizeof(methodTable[0]));
	return JNI_VERSION_1_6;
}


} // extern "C"
