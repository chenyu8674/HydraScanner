package com.pcl.lpr;

import android.Manifest;
import android.annotation.SuppressLint;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.hardware.Camera;
import android.os.AsyncTask;
import android.os.Bundle;
import android.support.v4.app.ActivityCompat;
import android.support.v7.app.AppCompatActivity;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.Window;
import android.view.WindowManager;
import android.widget.TextView;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.common.GlobalHistogramBinarizer;
import com.google.zxing.qrcode.QRCodeReader;
import com.pcl.lpr.utils.CodeHints;
import com.pcl.lpr.utils.DeepAssetUtil;
import com.pcl.lpr.utils.PlateRecognition;
import com.pcl.lpr.utils.ViewFinderView;

import org.opencv.android.BaseLoaderCallback;
import org.opencv.android.LoaderCallbackInterface;
import org.opencv.android.OpenCVLoader;
import org.opencv.android.Utils;
import org.opencv.core.CvType;
import org.opencv.core.Mat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

public class HydraActivity extends AppCompatActivity implements SurfaceHolder.Callback, Camera.PreviewCallback {
    public long handle;
    private Camera camera;
    private PalmTask mFaceTask;
    private ViewFinderView viewFinderView;// 选景框
    private TextView resultView;// 识别结果

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        setContentView(R.layout.activity_lpr);
        requestDangerousPermissions();
        viewFinderView = findViewById(R.id.view_finder_view);
        resultView = findViewById(R.id.view_result_view);
        SurfaceView mSurfaceView = findViewById(R.id.sv_camera_ui);
        SurfaceHolder surfaceHolder = mSurfaceView.getHolder();
        surfaceHolder.addCallback(this);
    }

    public void requestDangerousPermissions() {
        String[] strings = new String[]{Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE};
        ActivityCompat.requestPermissions(this, strings, 100);
    }

    @Override
    public void onResume() {
        super.onResume();
        if (!OpenCVLoader.initDebug()) {
            // 加载 openCV
            OpenCVLoader.initAsync(OpenCVLoader.OPENCV_VERSION_3_4_0, this, mLoaderCallback);
        } else {
            mLoaderCallback.onManagerConnected(LoaderCallbackInterface.SUCCESS);
        }
    }

    private BaseLoaderCallback mLoaderCallback = new BaseLoaderCallback(this) {
        @SuppressLint("StaticFieldLeak")
        @Override
        public void onManagerConnected(int status) {
            // openCV 加载成功, 继续加载 vcd so 文件
            if (status == LoaderCallbackInterface.SUCCESS) {
                System.loadLibrary("lpr");
                new AsyncTask<Void, Void, Void>() {
                    @Override
                    protected Void doInBackground(Void... voids) {
                        handle = DeepAssetUtil.initRecognizer(HydraActivity.this);
                        return null;
                    }
                }.execute();
            } else {
                super.onManagerConnected(status);
            }
        }
    };

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        camera = Camera.open(0);
        camera.setDisplayOrientation(90);
        Camera.Parameters parameters = camera.getParameters();
        parameters.setFlashMode(Camera.Parameters.FLASH_MODE_AUTO);
        parameters.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE);
        camera.setParameters(parameters);
        try {
            camera.setPreviewDisplay(holder);
        } catch (IOException e) {
            e.printStackTrace();
        }
        camera.setPreviewCallback(this);
        camera.startPreview();
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        if (camera != null) {
            camera.setPreviewCallback(null);
            camera.release();
            camera = null;
        }
    }

    @Override
    public void onPreviewFrame(byte[] data, Camera camera) {
        if (null != mFaceTask) {
            switch (mFaceTask.getStatus()) {
                case RUNNING:
                    return;
                case PENDING:
                    mFaceTask.cancel(false);
                    break;
            }
        }
        mFaceTask = new PalmTask(data);
        mFaceTask.execute();
    }

    @SuppressLint("StaticFieldLeak")
    private class PalmTask extends AsyncTask<Void, Void, String> {
        private byte[] mData;

        PalmTask(byte[] data) {
            this.mData = data;
        }

        @Override
        protected String doInBackground(Void... params) {
            Camera.Size size = camera.getParameters().getPreviewSize();
            try {
                YuvImage image = new YuvImage(mData, ImageFormat.NV21, size.width, size.height, null);
                ByteArrayOutputStream stream = new ByteArrayOutputStream(mData.length);
                if (!image.compressToJpeg(new Rect(0, 0, size.width, size.height), 100, stream)) {
                    return null;
                }
                Bitmap bmp = BitmapFactory.decodeByteArray(stream.toByteArray(), 0, stream.size());
                stream.close();
                // 截取选景框内容
                Matrix matrix = new Matrix();
                matrix.postRotate(90);
                Bitmap bitmap = Bitmap.createBitmap(bmp, viewFinderView.center.top, viewFinderView.center.left,
                        viewFinderView.centerHeight, viewFinderView.centerWidth, matrix, true);
                // 尝试使用zxing解析二维码
                Bitmap bitmapForQR = bitmap.copy(Bitmap.Config.ARGB_8888, true);
                Result result = decodeQR(bitmapForQR);
                if (result != null) {
                    String str = result.getText();
                    if (str != null && !"".equals(str)) {
                        new Thread(() -> runOnUiThread(() -> resultView.setText(str))).start();
                        return null;
                    }
                }
                // 二维码解析失败，继续尝试解析车牌
                int width = bitmap.getWidth();
                int height = bitmap.getHeight();
                Mat m = new Mat(width, height, CvType.CV_8UC4);
                Utils.bitmapToMat(bitmap, m);
                return PlateRecognition.SimpleRecognization(m.getNativeObjAddr(), handle);
            } catch (Exception ex) {
                ex.printStackTrace();
                return null;
            }
        }

        @Override
        protected void onPostExecute(String str) {
            super.onPostExecute(str);
            if (str != null && !"".equals(str)) {
                String[] list = str.split(",");
                resultView.setText(list[0]);
            }
        }
    }

    /**
     * 解析二维码图片
     */
    public static Result decodeQR(Bitmap bitmap) {
        Result result = null;
        if (bitmap != null) {
            int width = bitmap.getWidth();
            int height = bitmap.getHeight();
            int[] pixels = new int[width * height];
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
            RGBLuminanceSource source = new RGBLuminanceSource(width, height, pixels);
            BinaryBitmap binaryBitmap = new BinaryBitmap(new GlobalHistogramBinarizer(source));
            QRCodeReader reader = new QRCodeReader();
            try {
                result = reader.decode(binaryBitmap, CodeHints.getDefaultDecodeHints());// 开始解析
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        return result;
    }

}