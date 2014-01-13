/*
  Offscreen Android Views library for Qt

  Author:
  Sergey A. Galin <sergey.galin@gmail.com>

  Distrbuted under The BSD License

  Copyright (c) 2014, DoubleGIS, LLC.
  All rights reserved.

  Redistribution and use in source and binary forms, with or without
  modification, are permitted provided that the following conditions are met:

  * Redistributions of source code must retain the above copyright notice,
    this list of conditions and the following disclaimer.
  * Redistributions in binary form must reproduce the above copyright notice,
    this list of conditions and the following disclaimer in the documentation
    and/or other materials provided with the distribution.
  * Neither the name of the DoubleGIS, LLC nor the names of its contributors
    may be used to endorse or promote products derived from this software
    without specific prior written permission.

  THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
  AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO,
  THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
  ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS
  BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
  CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
  SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
  INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
  CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
  ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF
  THE POSSIBILITY OF SUCH DAMAGE.
*/

package ru.dublgis.offscreenview;

import java.lang.Thread;
import java.util.Set;
import java.util.List;
import java.util.LinkedList;
import java.util.Arrays;
import java.util.TreeSet;
import java.util.Locale;
import java.util.List;
import java.util.LinkedList;
import java.io.File;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.content.res.Configuration;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ConfigurationInfo;
import android.content.DialogInterface;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.method.MetaKeyKeyListener;
import android.text.InputType;
import android.util.Log;
import android.util.DisplayMetrics;
import android.view.ContextMenu;
import android.view.ContextMenu.ContextMenuInfo;
import android.view.View;
import android.view.ViewParent;
import android.view.KeyEvent;
import android.view.KeyCharacterMap;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.Window;
import android.view.WindowManager;
import android.view.WindowManager.LayoutParams;
import android.view.inputmethod.InputMethodManager;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;
import android.graphics.Canvas;

//! \todo Not compatible with official Qt5, used in getContextStatic()
import org.qt.core.QtApplicationBase;

import ru.dublgis.offscreenview.OffscreenViewHelper;

abstract class OffscreenView
{
    public static final String TAG = "Grym/OffscreenView";
    protected OffscreenViewHelper helper_ = null;

    static protected Activity getContextStatic()
    {
        //! \todo Use a way compatible with Qt 5
        return QtApplicationBase.getActivityStatic();
    }

    abstract public View getView();
    abstract public void callViewPaintMethod(Canvas canvas);
    abstract public void doInvalidateOffscreenView();
    abstract public void doResizeOffscreenView(final int width, final int height);
    abstract public void doNativeUpdate(long nativeptr);

    //! Schedule painting of the view (will be done in Android UI thread).
    protected void drawViewOnTexture()
    {
        Activity ctx = getContextStatic();
        if (ctx != null)
        {
            ctx.runOnUiThread(new Runnable()
            {
                @Override
                public void run()
                {
                    doDrawViewOnTexture();
                }
            });
        }
    }

    //! Performs actual painting of the view. Should be called in Android UI thread.
    protected void doDrawViewOnTexture()
    {
        if (helper_ == null)
        {
            Log.i(TAG, "doDrawViewOnTexture: helper is not initialized yet.");
            return;
        }
        synchronized(helper_)
        {
            if (helper_.getNativePtr() == 0)
            {
                Log.i(TAG, "doDrawViewOnTexture: zero native ptr, will not draw!");
                return;
            }
            try
            {
                // TODO: disable time measurement
                long t = System.nanoTime();
                Canvas canvas = helper_.lockCanvas();
                if (canvas == null)
                {
                    Log.e(TAG, "doDrawViewOnTexture: failed to lock canvas!");
                }
                else
                {
                    try
                    {
                        View v = getView();
                        if (v != null)
                        {
                            Log.i(TAG, "doDrawViewOnTexture texture="+helper_.getTexture()+", helper's texSize="+
                                helper_.getTextureWidth()+"x"+helper_.getTextureHeight()+
                                ", view size:"+v.getWidth()+"x"+v.getHeight());
                            callViewPaintMethod(canvas);
                        }
                        else
                        {
                            //! \todo Add ability to set fill color
                            Log.i(TAG, "doDrawViewOnTexture: View is not available yet, filling with white color....");
                            canvas.drawARGB(255, 255, 255, 255);
                        }
                    }
                    catch(Exception e)
                    {
                        Log.e(TAG, "doDrawViewOnTexture painting failed!", e);
                    }

                    helper_.unlockCanvas(canvas);

                    t = System.nanoTime() - t;

                    // Tell C++ part that we have a new image
                    doNativeUpdate(helper_.getNativePtr());

                    Log.i(TAG, "doDrawViewOnTexture: success, t="+t/1000000.0+"ms");
                }
            }
            catch(Exception e)
            {
                Log.e(TAG, "doDrawViewOnTexture exception:", e);
            }
        }
    }

    //! Called from C++ to get current texture.
    public boolean updateTexture()
    {
        if (helper_ == null)
        {
            return false;
        }
        synchronized(helper_)
        {
            // long t = System.nanoTime();
            boolean result = helper_.updateTexture();
            // Log.i(TAG, "updateTexture: "+t/1000000.0+"ms");
            return result;
        }
    }

    //! Called from C++ to notify us that the associated C++ object is being destroyed.
    public void cppDestroyed()
    {
        if (helper_ == null)
        {
            return;
        }
        synchronized(helper_)
        {
            Log.i(TAG, "cppDestroyed");
            helper_.cppDestroyed();
        }
    }

    //! Called from C++ to get texture coordinate transformation matrix (filled in updateTexture()).
    public float getTextureTransformMatrix(int index)
    {
        if (helper_ == null)
        {
            return 0;
        }
        synchronized(helper_)
        {
            return helper_.getTextureTransformMatrix(index);
        }
    }

    // TODO: refactor downt
    private long downt = 0;
    public void ProcessMouseEvent(final int action, final int x, final int y)
    {
        if (helper_.getNativePtr() == 0)
        {
            Log.i(TAG, "ProcessMouseEvent: zero native ptr, ignoring.");
            return;
        }

        final View view = getView();
        if (view != null)
        {
            final long t = SystemClock.uptimeMillis();
            if (action == MotionEvent.ACTION_DOWN || downt == 0)
            {

                downt = t;
            }
            Log.i(TAG, "ProcessMouseEvent("+action+", "+x+", "+y+") downt="+downt+", t="+t);
            final MotionEvent event = MotionEvent.obtain(downt /* downTime*/, t /* eventTime */, action, x, y, 0 /*metaState*/);
            Activity ctx = getContextStatic();
            if (ctx != null)
            {
                ctx.runOnUiThread(new Runnable()
                {
                    @Override
                    public void run()
                    {
                        view.onTouchEvent(event);
                        // TODO: If the view has only been scrolled, it won't call invalidate(). So we just force it to repaint for now.
                        // We should keep a larger piece of rendered HTML in the texture and only scroll the texture if possible.
                        doDrawViewOnTexture();
                    }
                });
            }
        }
    }

    // Called from C++ to force the view
    public void invalidateOffscreenView()
    {
        Log.i(TAG, "invalidateOffscreenView");
        if (getView() != null)
        {
            final Activity context = getContextStatic();
            context.runOnUiThread(new Runnable()
            {
                @Override
                public void run()
                {
                    doInvalidateOffscreenView();
                }
            });
        }
    }

    //! Called from C++ to change size of the view.
    public void resizeOffscreenView(final int w, final int h)
    {
        if (helper_ == null)
        {
            return;
        }
        synchronized(helper_)
        {
            Log.i(TAG, "resizeOffscreenView "+w+"x"+h);
            helper_.setNewSize(w, h);
            View view = getView();
            if (view != null)
            {
                final Activity context = getContextStatic();
                context.runOnUiThread(new Runnable()
                {
                    @Override
                    public void run()
                    {
                        doResizeOffscreenView(w, h);
                        doInvalidateOffscreenView();
                    }
                });
            }
        }
    }

    public boolean isViewCreated()
    {
        return getView() != null;
    }

    // C++ function called from Java to tell that the texture has new contents.
    // abstract public native void nativeUpdate(long nativeptr);

}