package  com.asus.toby.depth;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.ImageFormat;
import android.hardware.camera2.*;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Bundle;
import android.os.Handler;
import android.renderscript.Allocation;
import android.renderscript.Element;
import android.renderscript.ScriptIntrinsicYuvToRGB;
import android.renderscript.Type;
import android.util.Log;
import android.util.Pair;
import android.util.Size;
import android.view.Menu;
import android.view.MenuItem;
import android.view.Surface;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.media.Image.Plane;
import android.renderscript.RenderScript;

import java.util.HashMap;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import android.graphics.Point;
import com.intel.camera2.extensions.depthcamera.*;

/**
 * This sample illustrates the use of the Camera2 API and R200 Depth Extension
 * to show streaming Color and Depth data.
 *
 * This sample illustrates this using an Android ImageReaders, illustrating
 * application reading and processing of camera data directly.
 *
 * Note: Some conversion code is in C++ in this project for ease of reading, not
 * performance optimized code.
 */
public class DepthImage2LaserActivity extends Activity {
	private static final String TAG = "DepthImage2Laser";

	private CameraDevice mCamera;
	private CameraCharacteristics mCameraChar;
	private Size[] mColorSizes;
	private Size[] mDepthSizes;
	private Size[] mLaserSizes;
	private final Handler mHandler = new Handler();
	private static final int MAX_NUM_FRAMES = 5;
	private int mColorWidth;
	private int mColorHeight;
	private int mDepthWidth;
	private int mDepthHeight;
	private int mLaserWidth;
	private int mLaserHeight;
	private CameraCaptureSession mPreviewSession = null;
	private List<Pair<Surface, Integer>> mSurfaceList = new ArrayList<Pair<Surface, Integer>>();
	private boolean mRepeating = false;
    private TextView mTextView;
	private ImageView mColorView;
	private ImageView mDepthView;
	private ImageView mLaserView;
	private Bitmap mColorBitmap = null;
	private Bitmap mDepthBitmap = null;
	private Bitmap mLaserBitmap = null;
	private ByteBuffer mRGBByteBuffer;
	private ByteBuffer mDepthByteBuffer;
	private ByteBuffer mLaserByteBuffer;
	private SimpleRunnable mColorRunnable;
	private SimpleRunnable mDepthRunnable;
	private SimpleRunnable mLaserRunnable;
	private DepthCameraImageReader depthReader;
	private DepthCameraImageReader uvmapReader;
	private ImageReader colorReader;
	private android.util.Size uvmapForColorSize;
	private boolean mDisplayDepthAsUVMap = false;
	private ImageSynchronizer mImageSyncronizer;

	protected RenderScript mRS;
	private ScriptIntrinsicYuvToRGB mYuvConverter;
	private Allocation mAllocationYuv;
	private Allocation mAllocationARGB;
	protected byte[] mYuvBuffer;
	protected byte[] mOutRGBABuffer;

	// qiao@2015.10.13 pull from CameraSnapShotActivity
	protected DepthCameraCalibrationDataMap.IntrinsicParams mDepthCameraIntrinsics; //intrinsics param of depth camera
	private int mDepthCameraIndex = 2;
	/**
	 * A internal state object to prevent the app open the camera before its closing process is completed.
	 */
	private DepthCameraState mCurState = new DepthCameraState();

	public static String streamIdToText(int streamId){
		switch(streamId) {
			case DepthCameraStreamConfigurationMap.DEPTH_STREAM_SOURCE_ID:
				return "DEPTH_STREAM_SOURCE_ID";

			case DepthCameraStreamConfigurationMap.LEFT_STREAM_SOURCE_ID:
				return "LEFT_STREAM_SOURCE_ID";

			case DepthCameraStreamConfigurationMap.RIGHT_STREAM_SOURCE_ID:
				return "RIGHT_STREAM_SOURCE_ID";
		}

		return "<unknown streamId>: " + Integer.toHexString(streamId);
	}


	public static String formatToText(int format) {
		switch (format) {
			case ImageFormat.YUY2:
				return "ImageFormat.YUY2 (0x00000014)";

			case ImageFormat.YUV_420_888:
				return "ImageFormat.YUV_420_888 (0x00000023)";

			case ImageFormat.JPEG:
				return "JPEG";

			case ImageFormat.NV21:
				return "NV21";

			case ImageFormat.YV12:
				return "YV12";

			case PixelFormat.A_8:
				return "A_8";

			case PixelFormat.RGB_332:
				return "RGB_332";

			case PixelFormat.LA_88:
				return "LA_88";

			case PixelFormat.RGBA_4444:
				return "RGBA_4444";

			case PixelFormat.RGBA_5551:
				return "RGBA_5551";

			case PixelFormat.RGBA_8888:
				return "RGBA_8888";

			case DepthImageFormat.Z16:
				return "DepthImageFormat.Z16";

			case DepthImageFormat.UVMAP:
				return "DepthImageFormat.UVMAP";
		}

		return "<unknown format>: " + Integer.toHexString(format);
	}


	private class SimpleRunnable implements Runnable {
		private ImageView mView;
		private Bitmap mBitmap;

		SimpleRunnable( ImageView view, Bitmap bitmap ){
			mView = view;
			mBitmap = bitmap;
		}

		@Override
		public void run() {
			mView.setImageBitmap(mBitmap);
		}
	}


	public class ImageSynchronizer {
		private static final int COLOR = 0;
		private static final int DEPTH = 1;
		private static final int UVMAP = 2;
		private static final int MAX_TYPES = 3;

		private HashMap<Integer, ArrayList<Image> > mImageMap = new HashMap<Integer, ArrayList<Image> >();
		int[] mCounters = new int[MAX_TYPES];

		public ImageSynchronizer() {
		}

		public void addImageType(int type) {
			mImageMap.put(type, new ArrayList<Image>());
			mCounters[type] = 0;
		}

		public void notify(int type, Image data) {
			boolean all_available = false;

			ArrayList<Image> list = mImageMap.get(type);
			if( null != list ) {
				list.add(data);
				mCounters[type]++;
				all_available = true;
				for (int i = 0; i < MAX_TYPES; i++) {
					if (mImageMap.containsKey(i) && mCounters[i] <= 0) {
						all_available = false;
						break;
					}
				}
			}

			if (all_available) {
				Image colorImage = null;
				DepthImage depthImage = null;
				UVMAPImage uvImage = null;
				if (mImageMap.containsKey(DEPTH)) {
					depthImage = (DepthImage) mImageMap.get(DEPTH).remove(0);
					mCounters[DEPTH]--;
				}
				if (mImageMap.containsKey(COLOR)) {
					colorImage = (Image) mImageMap.get(COLOR).remove(0);
					mCounters[COLOR]--;
				}
				if (mImageMap.containsKey(UVMAP)) {
					uvImage = (UVMAPImage) mImageMap.get(UVMAP).remove(0);
					mCounters[UVMAP]--;
				}

				if(( null != colorImage) && (null != depthImage ) && (null != uvImage))
					onImageAvailable(colorImage,depthImage,uvImage);

				if (colorImage != null)
					colorImage.close();
				if (depthImage != null)
					depthImage.close();
				if (uvImage != null)
					uvImage.close();
			}
		}


		public void onImageAvailable(Image colorImage, DepthImage depthImage, UVMAPImage uvImage) {
			Plane[] planes = colorImage.getPlanes();
			assert(planes != null && planes.length > 0);

			Plane[] depthPlanes = depthImage.getPlanes();
			assert(depthPlanes != null && depthPlanes.length > 0);

			Plane[] uvmapPlanes = uvImage.getPlanes();
			assert(uvmapPlanes != null && uvmapPlanes.length > 0);

			// Process color using Renderscript, by packing planes
			// into single NV12 buffer then converting to RGBA
			//ByteBuffer buf = planes[0].getBuffer();
			//buf.get(mYuvBuffer, 0, buf.capacity());
			//int offset = buf.position();
			//buf = planes[2].getBuffer();
			//buf.get(mYuvBuffer, offset, buf.capacity());
			//offset += buf.position();
			//buf = planes[1].getBuffer();
			//buf.get(mYuvBuffer, offset, buf.capacity());

			//mAllocationYuv.copyFrom(mYuvBuffer);
			//mYuvConverter.forEach(mAllocationARGB);
			//mAllocationARGB.copyTo(mColorBitmap);

			//runOnUiThread(mColorRunnable);


			// Process Depth using C++ JNI code
			mDepthByteBuffer.rewind();

			if( mDisplayDepthAsUVMap )
			{
				// Obtain copy of RGBA data for use with JNI depth UVMap
				mAllocationARGB.copyTo(mOutRGBABuffer);
				mRGBByteBuffer.rewind();
				mRGBByteBuffer.put(mOutRGBABuffer);
				mRGBByteBuffer.rewind();

				uvMapToRGB( depthPlanes[0].getBuffer(), uvmapPlanes[0].getBuffer(), mRGBByteBuffer, mDepthByteBuffer, mDepthWidth, mDepthHeight, mColorWidth, mColorHeight);
			}
			else
			{
				depthToGrayscale( depthPlanes[0].getBuffer(), mDepthByteBuffer, mDepthWidth * mDepthHeight );
			}

			mDepthBitmap.copyPixelsFromBuffer(mDepthByteBuffer);
			runOnUiThread(mDepthRunnable);

			// qiao@2015.10.19: add DepthImage2Laser here
			// Process Depth using C++ JNI code
			mLaserByteBuffer.rewind();

			// qiao@2015.11.30:
			// a 1D laser is simulated where the depth is sampled along
			// a line which corresponds to the horizontal image scanline
			// through the center of the image.
			ArrayList<Point3DF> laserPoints = new ArrayList<Point3DF>();
			Integer width = depthImage.getWidth();
            // qiao@2015.12.09:
            // take a range of y into account (centerY +- dy)
			int center_y = depthImage.getHeight() / 2;
			for (int x=0; x<width; x++) {
                int idx_y;
                int dy = 5;
                int max_z = 0;
                int y = center_y;
                for(idx_y=center_y-dy; idx_y<center_y+dy; idx_y++){
                    int z = depthImage.getZ(x, idx_y);
                    if(depthImage.getZ(x, idx_y) > max_z) {
                        max_z = z;
                        y = idx_y;
                    }
                }
                if(max_z > 0) {
                    Point3DF point = depthImage.projectImageToWorldCoordinates(mDepthCameraIntrinsics, new Point(x, y));
                    laserPoints.add(point);
                }
			}

			drawPoints(laserPoints, mLaserByteBuffer, mLaserWidth, mLaserHeight, -1800F, 1800F, 0F, 6000F);
			mLaserBitmap.copyPixelsFromBuffer(mLaserByteBuffer);
			runOnUiThread(mLaserRunnable);
		}

		public void release() {
			Set<Integer> keys = mImageMap.keySet();

			for (Integer k : keys) {
				ArrayList<Image> list = mImageMap.get(k);
				list.clear();
			}
			mCounters = new int[MAX_TYPES];
		}
	}


	private class DepthImageAvailableListener implements DepthCameraImageReader.OnDepthCameraImageAvailableListener {
		@Override
		public void onDepthCameraImageAvailable(DepthCameraImageReader reader) {
			Image image = reader.acquireNextImage();
			if (image != null) {
				Plane[] planes = image.getPlanes();
				if (planes != null && planes[0] != null) {
					if (image instanceof DepthImage)
						mImageSyncronizer.notify(ImageSynchronizer.DEPTH,image);
					else if (image instanceof UVMAPImage)
						mImageSyncronizer.notify(ImageSynchronizer.UVMAP,image);
				}
			}
		}
	}


	private class ColorImageAvailableListener implements ImageReader.OnImageAvailableListener {
		@Override
		public void onImageAvailable(ImageReader reader) {
			Image image = reader.acquireNextImage();
			if (image != null) {
				Plane[] planes = image.getPlanes();
				if (planes != null && planes[0] != null)
					mImageSyncronizer.notify(ImageSynchronizer.COLOR, image);
			}
		}
	}


	public class SimpleCameraCaptureSession extends CameraCaptureSession.StateCallback {
		public SimpleCameraCaptureSession() {
		}

		@Override
		public void onConfigured(CameraCaptureSession cameraCaptureSession) {
			Log.d(TAG, "(cameraCaptureSession.StateCallback) onConfigured");
			mPreviewSession = cameraCaptureSession;
			createCameraPreviewRequest();
		}

		@Override
		public void onConfigureFailed(CameraCaptureSession cameraCaptureSession) {
			Log.d(TAG, "(cameraCaptureSession.StateCallback) onConfigureFailed");
		}
	}


	public class SimpleDeviceListener extends CameraDevice.StateCallback {

		public SimpleDeviceListener() {
		}

		@Override
		public void onDisconnected(CameraDevice camera) {
			Log.d( TAG,"(CameraDevice.StateCallback) onDisconnected" );
			mCurState.set(DepthCameraState.CAMERA_CLOSED);
		}

		@Override
		public void onError(CameraDevice camera, int error) {
			Log.d( TAG,"(CameraDevice.StateCallback) onError" );
			mCurState.set(DepthCameraState.CAMERA_CLOSED);
		}

		@Override
		public void onOpened(CameraDevice camera) {
			Log.d( TAG,"(CameraDevice.StateCallback) onOpened" );
			mCurState.set(DepthCameraState.CAMERA_OPENED);
			mCamera = camera;
			createCameraSession();
		}

		@Override
		public void onClosed(CameraDevice camera) {
			Log.d( TAG,"(CameraDevice.StateCallback) onClosed" );
			mCamera = null;
			mCurState.set(DepthCameraState.CAMERA_CLOSED);
		}
	}

	private void createCameraPreviewRequest()
	{
		Log.d( TAG, "createCameraPreviewRequest" );

		try {
			if (mCamera == null) return;
			CaptureRequest.Builder reqBldr = mCamera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);

			for (Pair<Surface, Integer> s : mSurfaceList)
				reqBldr.addTarget(s.first);

			//reqBldr.set(DepthCaptureRequest.R200_COLOR_RECTIFICATION_MODE, DepthCameraMetadata.R200_COLOR_RECTIFICATION_MODE_ON);

			Log.d(TAG, "R200_UVMAP_COLOR_SIZE requested: " + uvmapForColorSize.toString());
			reqBldr.set(DepthCaptureRequest.REALSENSE_UVMAP_COLOR_SIZE, uvmapForColorSize);

			mPreviewSession.setRepeatingRequest(reqBldr.build(), null, mHandler);
			mRepeating = true;
		} catch (CameraAccessException e) {
			Log.e(TAG, "In createCameraPreviewRequest(), Exception:" + e.getMessage());
			e.printStackTrace();
		}
	}


	private void createCameraSession()
	{
		Log.d( TAG, "createCameraSession" );
		mImageSyncronizer = new ImageSynchronizer();

		try {
			// NOTE: Sample is using hard coded resolutions: A robuse app should verify these exist from camera capabilities.

			// Used to display color
			mColorWidth =  1920;
			mColorHeight = 1080;

			mColorBitmap = Bitmap.createBitmap(mColorWidth, mColorHeight, Bitmap.Config.ARGB_8888);
			mColorRunnable = new SimpleRunnable( mColorView, mColorBitmap );
			mRGBByteBuffer = ByteBuffer.allocateDirect( mColorWidth * mColorHeight * 4);

			// Used to display either depth or uvmap
			mDepthWidth = 480;
			mDepthHeight = 360;
			mDepthBitmap = Bitmap.createBitmap(mDepthWidth, mDepthHeight, Bitmap.Config.ARGB_8888);
			mDepthByteBuffer = ByteBuffer.allocateDirect(mDepthWidth * mDepthHeight * 4);
			mDepthRunnable = new SimpleRunnable( mDepthView, mDepthBitmap );

			// Used to display laser points
			mLaserWidth = 480;
			mLaserHeight = 360;
			mLaserBitmap = Bitmap.createBitmap(mLaserWidth, mLaserHeight, Bitmap.Config.ARGB_8888);
			mLaserByteBuffer = ByteBuffer.allocateDirect(mLaserWidth * mLaserHeight * 4);
			mLaserRunnable = new SimpleRunnable( mLaserView, mLaserBitmap );

			uvmapForColorSize = new android.util.Size(mColorWidth, mColorHeight);


			// Setup Camera
			colorReader = ImageReader.newInstance(mColorWidth, mColorHeight, ImageFormat.YUV_420_888, MAX_NUM_FRAMES);
			colorReader.setOnImageAvailableListener(new ColorImageAvailableListener(), null);
			mSurfaceList.add(new Pair< Surface, Integer>(colorReader.getSurface(), DepthCameraStreamConfigurationMap.COLOR_STREAM_SOURCE_ID));
			mImageSyncronizer.addImageType(ImageSynchronizer.COLOR);

			depthReader = DepthCameraImageReader.newInstance(mDepthWidth, mDepthHeight, DepthImageFormat.Z16, MAX_NUM_FRAMES);
			depthReader.setOnImageAvailableListener(new DepthImageAvailableListener(), null);
			mSurfaceList.add(new Pair< Surface, Integer>(depthReader.getSurface(), DepthCameraStreamConfigurationMap.DEPTH_STREAM_SOURCE_ID));
			mImageSyncronizer.addImageType(ImageSynchronizer.DEPTH);

			uvmapReader = DepthCameraImageReader.newInstance(mDepthWidth, mDepthHeight, DepthImageFormat.UVMAP, MAX_NUM_FRAMES);
			uvmapReader.setOnImageAvailableListener(new DepthImageAvailableListener(), null);
			mSurfaceList.add(new Pair< Surface, Integer>(uvmapReader.getSurface(), DepthCameraStreamConfigurationMap.DEPTH_STREAM_SOURCE_ID));
			mImageSyncronizer.addImageType(ImageSynchronizer.UVMAP);


			// Setup RenderScript for YUV_420_888 to RGBA conversion in hardware
			mRS = RenderScript.create(this);
			mYuvConverter = ScriptIntrinsicYuvToRGB.create(mRS, Element.U8_4(mRS));

			Type.Builder yuvType = new Type.Builder(mRS, Element.U8(mRS)).setX(mColorWidth * mColorHeight * 3).setYuvFormat(ImageFormat.NV21);
			Type rgbaType = Type.createXY(mRS, Element.RGBA_8888(mRS), mColorWidth, mColorHeight);

			mYuvBuffer = new byte[mColorWidth * mColorHeight * 3];
			mOutRGBABuffer = new byte[mColorWidth * mColorHeight * 4];

			mAllocationARGB = Allocation.createTyped(mRS, rgbaType, Allocation.USAGE_SCRIPT | Allocation.USAGE_SHARED);
			mAllocationYuv = Allocation.createTyped(mRS, yuvType.create(), Allocation.USAGE_SCRIPT);

			mYuvConverter.setInput(mAllocationYuv);

			DepthCameraCaptureSessionConfiguration.createDepthCaptureSession(mCamera, mCameraChar, mSurfaceList, new SimpleCameraCaptureSession(), null );

			// qiao@2015.10.15: get intrinsics params of camera
			mColorWidth = 1920;
			mColorHeight = 1080;
			mDepthWidth = 480;
			mDepthHeight = 360;
			DepthCameraCalibrationDataMap calibDataMap = new DepthCameraCalibrationDataMap(mCameraChar, Integer.parseInt(mCamera.getId()));
			DepthCameraCalibrationDataMap.DepthCameraCalibrationData calibData =
				calibDataMap.getCalibrationData(new Size(mColorWidth, mColorHeight), new Size(mDepthWidth, mDepthHeight), /*not rectified*/ false, mDepthCameraIndex);
			mDepthCameraIntrinsics = calibData.getDepthCameraIntrinsics();
		}
		catch (Exception e) {
			Log.e(TAG, "In createCameraSession(), Exception:" + e.getMessage());
			e.printStackTrace();
		}
	}


	private void openCamera()
	{
		Log.d( TAG,"openCamera" );

		CameraManager camManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
		String cameraId = null;

		try {
			String[] cameraIds = camManager.getCameraIdList();
			if (cameraIds.length == 0)
				throw new Exception(TAG + ": camera ids list= 0");

			Log.w( TAG, "Number of cameras: " + cameraIds.length );

			for (int i = 0; i < cameraIds.length; i++) {
				Log.w(TAG, "Evaluating camera " + cameraIds[i] );

				mCameraChar = camManager.getCameraCharacteristics(cameraIds[i]);
				//mCameraChar.get(CameraCharacteristics.SENSOR_INFO_MAX_FRAME_DURATION);
				//mCameraChar.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE);
				try {
					if (DepthCameraCharacteristics.isDepthCamera(mCameraChar)) {
						cameraId = cameraIds[i];
						mDepthCameraIndex = i;
						break;
					}
				}
				catch (Exception e) {
					Log.w(TAG,"Camera " +cameraId + ": failed on isDepthCamera");
				}
			}

			if (cameraIds.length > 0 && cameraId == null && mCameraChar != null)
				throw new Exception(TAG + "No Depth Camera Found");

			if (cameraId != null) {
				// Color
				Log.d( TAG, "Camera " + cameraId + " color characteristics");
				StreamConfigurationMap configMap = mCameraChar.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);

				int[] colorFormats = configMap.getOutputFormats();
				for (int format : colorFormats)
				{
					Log.d( TAG,"Camera " + cameraId + ": Supports color format " + formatToText(format));
					Size[] mColorSizes = configMap.getOutputSizes(format);
					for( Size s : mColorSizes )
						Log.d( TAG,"Camera " + cameraId + ":     color size " + s.getWidth() + ", " + s.getHeight() );
				}

				// Depth
				int streamIds[] = {
					DepthCameraStreamConfigurationMap.DEPTH_STREAM_SOURCE_ID,
					DepthCameraStreamConfigurationMap.LEFT_STREAM_SOURCE_ID,
					DepthCameraStreamConfigurationMap.RIGHT_STREAM_SOURCE_ID
				};
				for( int streamId : streamIds )
				{
					Log.d( TAG, "Camera " + cameraId + " DepthCameraStreamConfigurationMap for " + streamIdToText(streamId));
					DepthCameraStreamConfigurationMap depthConfigMap = new DepthCameraStreamConfigurationMap(mCameraChar);

					int[] depthFormats = depthConfigMap.getOutputFormats(streamId);
					for (int format : depthFormats)
					{
						Log.d( TAG,"Camera " + cameraId + ": Supports depth format " + formatToText(format));

						Size[] sizes  = depthConfigMap.getOutputSizes(streamId, format);
						for( Size s : sizes )
							Log.d( TAG,"Camera " + cameraId + ":     color size " + s.getWidth() + ", " + s.getHeight() );
					}
				}
			}

			mCurState.set(DepthCameraState.CAMERA_OPENING);

			camManager.openCamera(cameraId, new SimpleDeviceListener(), mHandler);
		}
		catch (Exception e) {
			Log.e( TAG, "In openCamera(), Exception:" + e.getMessage());
			e.printStackTrace();

			Toast toast = Toast.makeText(getApplicationContext(), "No Intel® RealSense™ 3D camera detected", Toast.LENGTH_LONG);
			toast.show();
		}
	}


	private void closeCamera(){
		Log.d( TAG,"closeCamera" );

		try {
			mCurState.set(DepthCameraState.CAMERA_CLOSING);
			if (mCamera != null) {
				if (mRepeating) {
					mPreviewSession.stopRepeating();
					mRepeating = false;
				}

				mCamera.close();
			}
		}
		catch (Exception e) {
			Log.e( TAG, "In closeCamera(), Exception:" + e.getMessage());
			e.printStackTrace();
		}

		//Clean up memory allocated in the createCameraSessioin().
		if (mRS != null) {
			mRS.destroy();
			mRS = null;
		}

		mYuvBuffer = null;
		mOutRGBABuffer = null;

		if (mAllocationARGB != null) {
			mAllocationARGB.destroy();
			mAllocationARGB = null;
		}

		if (mAllocationYuv != null) {
			mAllocationYuv.destroy();
			mAllocationYuv = null;
		}

		if (mImageSyncronizer != null) {
			mImageSyncronizer.release();
			mImageSyncronizer = null;
		}

		if (mSurfaceList != null) {
			mSurfaceList.clear();
		}

		if (mRGBByteBuffer != null) {
			mRGBByteBuffer.clear();
			mRGBByteBuffer = null;
		}

		if (mDepthByteBuffer != null) {
			mDepthByteBuffer.clear();
			mDepthByteBuffer = null;
		}

		if (mLaserByteBuffer != null) {
			mLaserByteBuffer.clear();
			mLaserByteBuffer = null;
		}

		if (colorReader != null) {
			colorReader.close();
			colorReader = null;
		}

		if (depthReader != null) {
			depthReader.close();
			depthReader = null;
		}

		if (uvmapReader != null) {
			uvmapReader.close();
			uvmapReader = null;
		}

		mPreviewSession = null;
	}


	@Override
	protected void onCreate(Bundle savedInstanceState) {
		Log.d(TAG, "onCreate");

		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_main);
		mTextView = (TextView)findViewById(R.id.textView_log);
		mDepthView = (ImageView)findViewById(R.id.imageView_depth);
		mLaserView = (ImageView)findViewById(R.id.imageView_laser);
		//Set the default state of the state object
		mCurState.set(DepthCameraState.CAMERA_CLOSED);
	}


	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		getMenuInflater().inflate(R.menu.main, menu);
		return true;
	}


	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		switch (item.getItemId()) {

			case R.id.menu_options_uvmap:
				if (item.isChecked()){
					item.setChecked(false);
					mDisplayDepthAsUVMap = false;
				} else {
					item.setChecked(true);
					mDisplayDepthAsUVMap = true;
				}
				return true;

			case R.id.menu_info:
				new AlertDialog.Builder(this)
					.setMessage(R.string.intro_message)
					.setPositiveButton(android.R.string.ok, null)
					.show();
				return true;

			default:
				return super.onOptionsItemSelected(item);
		}

	}


	@Override
	public void onPause() {
		Log.d(TAG, "onPause, the state is " + mCurState);
		//If the camera was not close, go ahead and close it again.
		if (mCurState.get() != DepthCameraState.CAMERA_CLOSED) closeCamera();
		super.onPause();
	}


	@Override
	public void onResume() {
		Log.d( TAG,"onResume, the state is " + mCurState);
		super.onResume();
		//Check the current state, only call the openCamera() if the camera closing process
		// was completed.
		if (mCurState.get() == DepthCameraState.CAMERA_CLOSED) openCamera();
			//If the camera is in a process of closing, we can wait for a while and call openCamera()
		else if (mCurState.get() == DepthCameraState.CAMERA_CLOSING) {
			try {
				Thread.sleep(500);
			} catch (InterruptedException e) {
				Log.d(TAG, "onResume(): the waiting timer was interrupted.\nTrace message:\n" + e.getLocalizedMessage());
			}
			openCamera();
		}
	}


	@Override
	public void onDestroy() {
		Log.d( TAG,"onDestroy");
		super.onDestroy();
	}

	private class DepthCameraState {
		public final static int CAMERA_OPENING = 0x10;
		public final static int CAMERA_OPENED = 0x11;
		public final static int CAMERA_CLOSING = 0x12;
		public final static int CAMERA_CLOSED = 0x13;

		private int mState = CAMERA_CLOSED;

		public DepthCameraState() {
			mState = CAMERA_CLOSED;
		}

		public synchronized void set(int state) {
			mState = state;
			Log.d(TAG, "Camera State: " + toString());
		}

		public int get() {
			return mState;
		}

		@Override
		public String toString() {
			String retVal = "NO State";
			switch (mState) {
				case CAMERA_OPENING:
					retVal = "CAMERA_OPENING";
					break;
				case CAMERA_OPENED:
					retVal = "CAMERA_OPENED";
					break;
				case CAMERA_CLOSING:
					retVal = "CAMERA_CLOSING";
					break;
				case CAMERA_CLOSED:
					retVal = "CAMERA_CLOSED";
					break;
			}

			return retVal;
		}
	}
	private native boolean drawPoints(ArrayList laserPoints, ByteBuffer laserByteBuffer, int laserWidth, int laserHeight, float minWorldX, float maxWorldX, float minWorldY, float maxWorldY);
	private native boolean depthToGrayscale( ByteBuffer depthBuf, ByteBuffer grayscaleBuf, int bufSize);
	private native boolean uvMapToRGB( ByteBuffer depthBufSrc, ByteBuffer uvMapSrc, ByteBuffer colorPixelsSrc, ByteBuffer dst, int depthWidth, int depthHeight, int colorWidth, int colorHeight);

	static {
		System.loadLibrary("jni_Depth2Laser");
	}
}
