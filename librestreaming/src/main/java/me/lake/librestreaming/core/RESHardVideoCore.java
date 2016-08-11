package me.lake.librestreaming.core;

import android.graphics.Bitmap;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.opengl.EGL14;
import android.opengl.EGLExt;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;
import android.util.Log;
import android.view.Surface;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import me.lake.librestreaming.client.CallbackDelivery;
import me.lake.librestreaming.core.listener.RESScreenShotListener;
import me.lake.librestreaming.filter.hardvideofilter.BaseHardVideoFilter;
import me.lake.librestreaming.model.MediaCodecGLWapper;
import me.lake.librestreaming.model.RESConfig;
import me.lake.librestreaming.model.RESCoreParameters;
import me.lake.librestreaming.model.ScreenGLWapper;
import me.lake.librestreaming.rtmp.RESFlvDataCollecter;
import me.lake.librestreaming.tools.LogTools;

/**
 * Created by lake on 16-5-24.
 */
public class RESHardVideoCore implements RESVideoCore {
    RESCoreParameters resCoreParameters;
    private final Object syncOp = new Object();
    private int currentCamera;
    //filter
    private Lock lockVideoFilter = null;
    private BaseHardVideoFilter videoFilter;
    private MediaCodec dstVideoEncoder;
    private MediaFormat dstVideoFormat;
    private final Object syncPreview = new Object();
    private final Object syncScreenCleanUp = new Object();
    private ScreenParams screenParams;
    private HandlerThread videoGLHandlerThread;
    private Handler videoGLHander;
    //sender
    private VideoSenderThread videoSenderThread;

    final private Object syncResScreenShotListener = new Object();
    private RESScreenShotListener resScreenShotListener;
    private boolean isStreaming = false;

    public RESHardVideoCore(RESCoreParameters parameters) {
        resCoreParameters = parameters;
        screenParams = new ScreenParams();
        lockVideoFilter = new ReentrantLock(false);
    }

    public void onFrameAvailable() {
        if (videoGLHandlerThread != null) {
            Log.e("aa", "123");
            videoGLHandlerThread.wakeup();
        }
    }

    @Override
    public boolean prepare(RESConfig resConfig) {
        synchronized (syncOp) {
            resCoreParameters.renderingMode = resConfig.getRenderingMode();
            resCoreParameters.mediacdoecAVCBitRate = resConfig.getBitRate();
            resCoreParameters.videoBufferQueueNum = resConfig.getVideoBufferQueueNum();
            resCoreParameters.mediacodecAVCFrameRate = 30;
            resCoreParameters.mediacodecAVCIFrameInterval = 5;
            dstVideoFormat = new MediaFormat();
            dstVideoEncoder = MediaCodecHelper.createHardVideoMediaCodec(resCoreParameters, dstVideoFormat);
            if (dstVideoEncoder == null) {
                LogTools.e("create Video MediaCodec failed");
                return false;
            }
            return true;
        }
    }

    @Override
    public boolean startPreview(SurfaceTexture camTex) {
        synchronized (syncOp) {
            try {
                if (dstVideoEncoder == null) {
                    dstVideoEncoder = MediaCodec.createEncoderByType(dstVideoFormat.getString(MediaFormat.KEY_MIME));
                }
                dstVideoEncoder.configure(dstVideoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                videoGLHandlerThread = new HanderThr
                videoGLHandlerThread.start();
            } catch (Exception e) {
                LogTools.trace("RESHardVideoCore,start()failed", e);
                return false;
            }
            return true;
        }
    }

    @Override
    public boolean startStreaming(RESFlvDataCollecter flvDataCollecter) {
        try {
            dstVideoEncoder.start();
        } catch (Exception e) {
            dstVideoEncoder.configure(dstVideoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            dstVideoEncoder.start();
        }
        videoSenderThread = new VideoSenderThread("VideoSenderThread", dstVideoEncoder, flvDataCollecter);
        videoSenderThread.start();
        isStreaming = true;
        return true;
    }

    @Override
    public void updateCamTexture(SurfaceTexture camTex) {
        synchronized (syncOp) {
            if (videoGLHandlerThread != null) {
                videoGLHandlerThread.updateCamTexture(camTex);
            }
        }
    }

    @Override
    public boolean stopStreaming() {
        videoSenderThread.quit();
        try {
            videoSenderThread.join();
        } catch (InterruptedException e) {
            LogTools.trace("RESHardVideoCore,stopStreaming()failed", e);
            return false;
        }
        videoSenderThread = null;
        dstVideoEncoder.stop();
        isStreaming = false;
        return true;
    }

    @Override
    public boolean stopPreview() {
        synchronized (syncOp) {
            videoGLHandlerThread.quit();
            try {
                videoGLHandlerThread.join();
            } catch (InterruptedException e) {
                LogTools.trace("RESHardVideoCore,stopPreview()failed", e);
                return false;
            }
            videoGLHandlerThread = null;
            dstVideoEncoder.release();
            dstVideoEncoder = null;
            return true;
        }
    }

    @Override
    public boolean destroy() {
        synchronized (syncOp) {
            return true;
        }
    }

    @Override
    public void setCurrentCamera(int cameraIndex) {
        currentCamera = cameraIndex;
        if (videoGLHandlerThread != null) {
            videoGLHandlerThread.updateCameraIndex(currentCamera);
        }
    }

    @Override
    public void createPreview(SurfaceTexture surfaceTexture, int visualWidth, int visualHeight) {
        synchronized (syncPreview) {
            if (screenParams.surfaceTexture != null) {
                throw new RuntimeException("createPreview without destroyPreview");
            }
            screenParams.surfaceTexture = surfaceTexture;
            screenParams.visualWidth = visualWidth;
            screenParams.visualHeight = visualHeight;
        }
    }

    @Override
    public void updatePreview(int visualWidth, int visualHeight) {
        synchronized (syncPreview) {
            screenParams.visualWidth = visualWidth;
            screenParams.visualHeight = visualHeight;
        }
    }

    @Override
    public void destroyPreview() {
        synchronized (syncOp) {
            if (videoGLHandlerThread != null) {
                synchronized (syncScreenCleanUp) {
                    try {
                        synchronized (syncPreview) {
                            screenParams.surfaceTexture = null;
                        }
                        if (!videoGLHandlerThread.screenCleaned) {
                            videoGLHandlerThread.wakeup();
                            syncScreenCleanUp.wait();
                        }
                    } catch (InterruptedException ignored) {
                    }
                }
            }
        }
    }

    public BaseHardVideoFilter acquireVideoFilter() {
        lockVideoFilter.lock();
        return videoFilter;
    }

    public void releaseVideoFilter() {
        lockVideoFilter.unlock();
    }

    public void setVideoFilter(BaseHardVideoFilter baseHardVideoFilter) {
        lockVideoFilter.lock();
        videoFilter = baseHardVideoFilter;
        lockVideoFilter.unlock();
    }

    @Override
    public void takeScreenShot(RESScreenShotListener listener) {
        synchronized (syncResScreenShotListener) {
            resScreenShotListener = listener;
        }
    }

    @Override
    public float getDrawFrameRate() {
        synchronized (syncOp) {
            return videoGLHandlerThread == null ? 0 : videoGLHandlerThread.getDrawFrameRate();
        }
    }

    private class VideoGLHandler extends Handler {
        static final int WHAT_FRAME = 1;
        static final int WHAT_CREATE_PREVIEW = 2;
        static final int WHAT_DESTROY_PREVIEW = 3;
        static final int WHAT_START_STREAMING = 4;
        static final int WHAT_STOP_STREAMING = 5;
        //=========================
        public static final int FILTER_LOCK_TOLERATION = 3;//3ms
        private boolean quit;
        private final Object syncThread = new Object();
        public boolean screenCleaned = false;
        private int frameNum = 0;
        //gl stuff
        private Surface mediaInputSurface;
        private SurfaceTexture cameraTexture;
        private MediaCodecGLWapper mediaCodecGLWapper;
        private ScreenGLWapper screenGLWapper;
        private int sample2DFrameBuffer;
        private int sample2DFrameBufferTexture;
        private int frameBuffer;
        private int frameBufferTexture;
        private FloatBuffer shapeVerticesBuffer;
        private FloatBuffer mediaCodecTextureVerticesBuffer;
        private FloatBuffer screenTextureVerticesBuffer;
        private int currCamera;
        private final Object syncCameraTextureVerticesBuffer = new Object();
        private FloatBuffer camera2dTextureVerticesBuffer;
        private FloatBuffer cameraTextureVerticesBuffer;
        private ShortBuffer drawIndecesBuffer;
        private BaseHardVideoFilter innerVideoFilter = null;
        private RESFrameRateMeter drawFrameRateMeter;
        private int directionFlag;

        public VideoGLHandler(SurfaceTexture camTexture, int cameraIndex)
        {
            screenCleaned = false;
            cameraTexture = camTexture;
            screenGLWapper = null;
            mediaCodecGLWapper = null;
            quit = false;
            currCamera = cameraIndex;
            drawFrameRateMeter = new RESFrameRateMeter();
            initBuffer();
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case WHAT_FRAME: {
                }
                break;
                case WHAT_CREATE_PREVIEW: {
                }
                break;
                case WHAT_DESTROY_PREVIEW: {

                }
                break;
                case WHAT_START_STREAMING: {

                }
                break;
                case WHAT_STOP_STREAMING: {
                }
                break;
                default:
            }
        }
        private void initBuffer() {
            shapeVerticesBuffer = GLHelper.getShapeVerticesBuffer();
            mediaCodecTextureVerticesBuffer = GLHelper.getMediaCodecTextureVerticesBuffer();
            screenTextureVerticesBuffer = GLHelper.getScreenTextureVerticesBuffer();
            updateCameraIndex(currCamera);
            drawIndecesBuffer = GLHelper.getDrawIndecesBuffer();
            cameraTextureVerticesBuffer = GLHelper.getCameraTextureVerticesBuffer();
        }
        public void updateCameraIndex(int cameraIndex) {
            synchronized (syncCameraTextureVerticesBuffer) {
                currCamera = cameraIndex;
                if (currCamera == Camera.CameraInfo.CAMERA_FACING_FRONT) {
                    directionFlag = resCoreParameters.frontCameraDirectionMode;
                } else {
                    directionFlag = resCoreParameters.backCameraDirectionMode;
                }
                camera2dTextureVerticesBuffer = GLHelper.getCamera2DTextureVerticesBuffer(directionFlag, resCoreParameters.cropRatio);
            }
        }
    }

    private class VideoGLThread extends Thread {
        public static final int FILTER_LOCK_TOLERATION = 3;//3ms
        private boolean quit;
        private final Object syncThread = new Object();
        public boolean screenCleaned = false;
        private int frameNum = 0;
        //gl stuff
        private Surface mediaInputSurface;
        private SurfaceTexture cameraTexture;
        private MediaCodecGLWapper mediaCodecGLWapper;
        private ScreenGLWapper screenGLWapper;
        private int sample2DFrameBuffer;
        private int sample2DFrameBufferTexture;
        private int frameBuffer;
        private int frameBufferTexture;
        private FloatBuffer shapeVerticesBuffer;
        private FloatBuffer mediaCodecTextureVerticesBuffer;
        private FloatBuffer screenTextureVerticesBuffer;
        private int currCamera;
        private final Object syncCameraTextureVerticesBuffer = new Object();
        private FloatBuffer camera2dTextureVerticesBuffer;
        private FloatBuffer cameraTextureVerticesBuffer;
        private ShortBuffer drawIndecesBuffer;
        private BaseHardVideoFilter innerVideoFilter = null;
        private RESFrameRateMeter drawFrameRateMeter;
        private int directionFlag;

        VideoGLThread(SurfaceTexture camTexture, Surface inputSuface, int cameraIndex) {
            screenCleaned = false;
            mediaInputSurface = inputSuface;
            cameraTexture = camTexture;
            screenGLWapper = null;
            mediaCodecGLWapper = null;
            quit = false;
            currCamera = cameraIndex;
            drawFrameRateMeter = new RESFrameRateMeter();
        }

        public float getDrawFrameRate() {
            return drawFrameRateMeter.getFps();
        }


        public void updateCamTexture(SurfaceTexture surfaceTexture) {
            if (surfaceTexture != cameraTexture) {
                cameraTexture = surfaceTexture;
                frameNum = 0;
            }
        }

        public void quit() {
            synchronized (syncThread) {
                quit = true;
                syncThread.notify();
            }
        }

        public void wakeup() {
            synchronized (syncThread) {
                ++frameNum;
                syncThread.notify();
            }
        }

        @Override
        public void run() {
            initBuffer();
            mediaCodecGLWapper = new MediaCodecGLWapper();
            GLHelper.initMediaCodecGL(mediaCodecGLWapper, mediaInputSurface);
            GLHelper.currentMediaCodec(mediaCodecGLWapper);
            int[] fb = new int[1], fbt = new int[1];
            GLHelper.createCamFrameBuff(fb, fbt, resCoreParameters.videoWidth, resCoreParameters.videoHeight);
            sample2DFrameBuffer = fb[0];
            sample2DFrameBufferTexture = fbt[0];
            GLHelper.createCamFrameBuff(fb, fbt, resCoreParameters.videoWidth, resCoreParameters.videoHeight);
            initMediaCodecProgram(mediaCodecGLWapper);
            frameBuffer = fb[0];
            frameBufferTexture = fbt[0];
            while (!quit) {
                Log.e("aa", "startOneFrame");
                GLHelper.currentMediaCodec(mediaCodecGLWapper);
                waitCamera();
                Log.e("aa", "startOneFrame1");
                if (quit) {
                    break;
                }
                synchronized (syncThread) {
                    if (cameraTexture != null) {
                        cameraTexture.updateTexImage();
                    }
                }
                Log.e("aa", "startOneFrame2");
                drawSample2DFrameBuffer();
                drawFrameBuffer();
                Log.e("aa", "startOneFrame3");
                if (isStreaming) {
                    drawMediaCodec();
                }
                Log.e("aa", "startOneFrame4");
                drawScreen();
                Log.e("aa", "startOneFrame5");
                drawFrameRateMeter.count();
                synchronized (syncThread) {
                    frameNum--;
                }
                Log.e("aa", "stopOneFrame");
            }
            lockVideoFilter.lock();
            if (innerVideoFilter != null) {
                innerVideoFilter.onDestroy();
                innerVideoFilter = null;
            }
            lockVideoFilter.unlock();
            GLHelper.currentMediaCodec(mediaCodecGLWapper);
            synchronized (syncPreview) {
                synchronized (syncScreenCleanUp) {
                    if (screenGLWapper != null) {
                        cleanUpScreen();
                    }
                    syncScreenCleanUp.notify();
                }
            }
            cleanUpMedia();
        }

        private void waitCamera() {
            synchronized (syncThread) {
                if (cameraTexture != null) {
                    while (frameNum >= 2) {
                        cameraTexture.updateTexImage();
                        --frameNum;
                    }
                }
                if (frameNum == 0) {
                    try {
                        if (!quit) {
                            syncThread.wait();
                        }
                    } catch (InterruptedException ignored) {
                    }
                }
            }
        }

        private void drawFrame() {
            GLES20.glClearColor(0.0f, 0.0f, 0.0f, 0.0f);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            GLES20.glDrawElements(GLES20.GL_TRIANGLES, drawIndecesBuffer.limit(), GLES20.GL_UNSIGNED_SHORT, drawIndecesBuffer);
        }

        private void drawSample2DFrameBuffer() {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, sample2DFrameBuffer);
            GLES20.glUseProgram(mediaCodecGLWapper.cam2dProgram);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, OVERWATCH_TEXTURE_ID);
            GLES20.glUniform1i(mediaCodecGLWapper.cam2dTextureLoc, 0);
            synchronized (syncCameraTextureVerticesBuffer) {
                GLHelper.enableVertex(mediaCodecGLWapper.cam2dPostionLoc, mediaCodecGLWapper.cam2dTextureCoordLoc,
                        shapeVerticesBuffer, camera2dTextureVerticesBuffer);
            }
            GLES20.glViewport(0, 0, resCoreParameters.videoWidth, resCoreParameters.videoHeight);
            drawFrame();
            GLES20.glFinish();
            GLHelper.disableVertex(mediaCodecGLWapper.cam2dPostionLoc, mediaCodecGLWapper.cam2dTextureCoordLoc);
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0);
            GLES20.glUseProgram(0);
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        }

        private void drawOriginFrameBuffer() {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, frameBuffer);
            GLES20.glUseProgram(mediaCodecGLWapper.camProgram);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, sample2DFrameBufferTexture);
            GLES20.glUniform1i(mediaCodecGLWapper.camTextureLoc, 0);
            synchronized (syncCameraTextureVerticesBuffer) {
                GLHelper.enableVertex(mediaCodecGLWapper.camPostionLoc, mediaCodecGLWapper.camTextureCoordLoc,
                        shapeVerticesBuffer, cameraTextureVerticesBuffer);
            }
            GLES20.glViewport(0, 0, resCoreParameters.videoWidth, resCoreParameters.videoHeight);
            drawFrame();
            GLES20.glFinish();
            GLHelper.disableVertex(mediaCodecGLWapper.camPostionLoc, mediaCodecGLWapper.camTextureCoordLoc);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
            GLES20.glUseProgram(0);
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        }

        private void drawFrameBuffer() {
            boolean isFilterLocked = lockVideoFilter();
            if (isFilterLocked) {
                if (videoFilter != innerVideoFilter) {
                    if (innerVideoFilter != null) {
                        innerVideoFilter.onDestroy();
                    }
                    innerVideoFilter = videoFilter;
                    if (innerVideoFilter != null) {
                        innerVideoFilter.onInit(resCoreParameters.videoWidth, resCoreParameters.videoHeight);
                    }
                }
                if (innerVideoFilter != null) {
                    synchronized (syncCameraTextureVerticesBuffer) {
                        innerVideoFilter.onDirectionUpdate(directionFlag);
                        innerVideoFilter.onDraw(sample2DFrameBufferTexture, frameBuffer, shapeVerticesBuffer, cameraTextureVerticesBuffer);
                    }
                } else {
                    drawOriginFrameBuffer();
                }
                unlockVideoFilter();
            } else {
                drawOriginFrameBuffer();
            }
        }

        private void drawMediaCodec() {
            GLES20.glUseProgram(mediaCodecGLWapper.drawProgram);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, frameBufferTexture);
            GLES20.glUniform1i(mediaCodecGLWapper.drawTextureLoc, 0);
            GLHelper.enableVertex(mediaCodecGLWapper.drawPostionLoc, mediaCodecGLWapper.drawTextureCoordLoc,
                    shapeVerticesBuffer, mediaCodecTextureVerticesBuffer);
            Log.e("aa", "drawMediaCodec1");
            drawFrame();
            Log.e("aa", "drawMediaCodec2");
            GLES20.glFinish();
            Log.e("aa", "drawMediaCodec3");
            checkScreenShot();
            Log.e("aa", "drawMediaCodec4");
            GLHelper.disableVertex(mediaCodecGLWapper.drawPostionLoc, mediaCodecGLWapper.drawTextureCoordLoc);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
            GLES20.glUseProgram(0);
            EGLExt.eglPresentationTimeANDROID(mediaCodecGLWapper.eglDisplay, mediaCodecGLWapper.eglSurface, cameraTexture.getTimestamp());
            Log.e("aa", "drawMediaCodec5");
            if (!EGL14.eglSwapBuffers(mediaCodecGLWapper.eglDisplay, mediaCodecGLWapper.eglSurface)) {
                throw new RuntimeException("eglSwapBuffers,failed!");
            }
            Log.e("aa", "drawMediaCodec6");
        }

        private void drawScreen() {
            synchronized (syncPreview) {
                if (screenParams.surfaceTexture == null) {
                    synchronized (syncScreenCleanUp) {
                        if (screenGLWapper != null) {
                            cleanUpScreen();
                            screenGLWapper = null;
                        }
                        syncScreenCleanUp.notify();
                        if (screenGLWapper == null) {
                            return;
                        }
                    }
                }
                if (screenGLWapper == null) {
                    screenCleaned = false;
                    screenGLWapper = new ScreenGLWapper();
                    GLHelper.initScreenGL(screenGLWapper, mediaCodecGLWapper.eglContext, screenParams.surfaceTexture);
                    initScreenProgram(screenGLWapper);
                }
                GLHelper.currentScreen(screenGLWapper);
                //drawScreen
                GLES20.glUseProgram(screenGLWapper.drawProgram);
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, frameBufferTexture);
                GLES20.glUniform1i(screenGLWapper.drawTextureLoc, 0);
                GLHelper.enableVertex(screenGLWapper.drawPostionLoc, screenGLWapper.drawTextureCoordLoc,
                        shapeVerticesBuffer, screenTextureVerticesBuffer);
                GLES20.glViewport(0, 0, screenParams.visualWidth, screenParams.visualHeight);
                drawFrame();
                GLHelper.disableVertex(screenGLWapper.drawPostionLoc, screenGLWapper.drawTextureCoordLoc);
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
                GLES20.glUseProgram(0);
                if (!EGL14.eglSwapBuffers(screenGLWapper.eglDisplay, screenGLWapper.eglSurface)) {
                    throw new RuntimeException("eglSwapBuffers,failed!");
                }
            }
        }

        private void initMediaCodecProgram(MediaCodecGLWapper wapper) {
            GLES20.glEnable(GLES11Ext.GL_TEXTURE_EXTERNAL_OES);
            //mediacodec
            wapper.drawProgram = GLHelper.createMediaCodecProgram();
            GLES20.glUseProgram(wapper.drawProgram);
            wapper.drawTextureLoc = GLES20.glGetUniformLocation(wapper.drawProgram, "uTexture");
            wapper.drawPostionLoc = GLES20.glGetAttribLocation(wapper.drawProgram, "aPosition");
            wapper.drawTextureCoordLoc = GLES20.glGetAttribLocation(wapper.drawProgram, "aTextureCoord");
            //camera
            wapper.camProgram = GLHelper.createCameraProgram();
            GLES20.glUseProgram(wapper.camProgram);
            wapper.camTextureLoc = GLES20.glGetUniformLocation(wapper.camProgram, "uTexture");
            wapper.camPostionLoc = GLES20.glGetAttribLocation(wapper.camProgram, "aPosition");
            wapper.camTextureCoordLoc = GLES20.glGetAttribLocation(wapper.camProgram, "aTextureCoord");
            //camera2d
            wapper.cam2dProgram = GLHelper.createCamera2DProgram();
            GLES20.glUseProgram(wapper.cam2dProgram);
            wapper.cam2dTextureLoc = GLES20.glGetUniformLocation(wapper.cam2dProgram, "uTexture");
            wapper.cam2dPostionLoc = GLES20.glGetAttribLocation(wapper.cam2dProgram, "aPosition");
            wapper.cam2dTextureCoordLoc = GLES20.glGetAttribLocation(wapper.cam2dProgram, "aTextureCoord");
        }

        private void initScreenProgram(ScreenGLWapper wapper) {
            wapper.drawProgram = GLHelper.createScreenProgram();
            GLES20.glUseProgram(wapper.drawProgram);
            wapper.drawTextureLoc = GLES20.glGetUniformLocation(wapper.drawProgram, "uTexture");
            wapper.drawPostionLoc = GLES20.glGetAttribLocation(wapper.drawProgram, "aPosition");
            wapper.drawTextureCoordLoc = GLES20.glGetAttribLocation(wapper.drawProgram, "aTextureCoord");
        }

        private void cleanUpMedia() {
            GLHelper.currentMediaCodec(mediaCodecGLWapper);
            GLES20.glDeleteProgram(mediaCodecGLWapper.camProgram);
            GLES20.glDeleteProgram(mediaCodecGLWapper.drawProgram);
            GLES20.glDeleteFramebuffers(1, new int[]{frameBuffer}, 0);
            GLES20.glDeleteTextures(1, new int[]{frameBufferTexture}, 0);
            EGL14.eglDestroySurface(mediaCodecGLWapper.eglDisplay, mediaCodecGLWapper.eglSurface);
            EGL14.eglDestroyContext(mediaCodecGLWapper.eglDisplay, mediaCodecGLWapper.eglContext);
            EGL14.eglTerminate(mediaCodecGLWapper.eglDisplay);
            EGL14.eglMakeCurrent(mediaCodecGLWapper.eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
        }

        private void cleanUpScreen() {
            screenCleaned = true;
            GLHelper.currentScreen(screenGLWapper);
            GLES20.glDeleteProgram(screenGLWapper.drawProgram);
            EGL14.eglDestroySurface(screenGLWapper.eglDisplay, screenGLWapper.eglSurface);
            EGL14.eglDestroyContext(screenGLWapper.eglDisplay, screenGLWapper.eglContext);
            EGL14.eglTerminate(screenGLWapper.eglDisplay);
        }

        /**
         * @return ture if filter locked & filter!=null
         */

        private boolean lockVideoFilter() {
            try {
                return lockVideoFilter.tryLock(FILTER_LOCK_TOLERATION, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                return false;
            }
        }

        private void unlockVideoFilter() {
            lockVideoFilter.unlock();
        }

        private void checkScreenShot() {
            synchronized (syncResScreenShotListener) {
                if (resScreenShotListener != null) {
                    Bitmap result = null;
                    try {
                        IntBuffer pixBuffer = IntBuffer.allocate(resCoreParameters.videoWidth * resCoreParameters.videoHeight);
                        GLES20.glReadPixels(0, 0, resCoreParameters.videoWidth, resCoreParameters.videoHeight, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixBuffer);
                        int[] glPixel = pixBuffer.array();
                        int[] argbPixel = new int[resCoreParameters.videoWidth * resCoreParameters.videoHeight];
                        ColorHelper.FIXGLPIXEL(glPixel, argbPixel, resCoreParameters.videoWidth, resCoreParameters.videoHeight);
                        result = Bitmap.createBitmap(argbPixel,
                                resCoreParameters.videoWidth,
                                resCoreParameters.videoHeight,
                                Bitmap.Config.ARGB_8888);
                    } catch (Exception e) {
                        LogTools.trace("takescreenshot failed:", e);
                    } finally {
                        CallbackDelivery.i().post(new RESScreenShotListener.RESScreenShotListenerRunable(resScreenShotListener, result));
                        resScreenShotListener = null;
                    }
                }
            }
        }
    }

    class ScreenParams {
        int visualWidth;
        int visualHeight;
        SurfaceTexture surfaceTexture;
    }
}
