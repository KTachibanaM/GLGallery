/*
 * Copyright 2016 Hippo Seven
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hippo.glgallery.example;

import android.content.res.Resources;
import android.os.Process;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;

import com.hippo.glgallery.GalleryProvider;
import com.hippo.image.Image;
import com.hippo.image.ImageData;
import com.hippo.unifile.FilenameFilter;
import com.hippo.unifile.UniFile;
import com.hippo.yorozuya.IOUtils;
import com.hippo.yorozuya.StringUtils;
import com.hippo.yorozuya.thread.PriorityThread;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Stack;
import java.util.concurrent.atomic.AtomicInteger;

public class DirGalleryProvider extends GalleryProvider implements Runnable {

    private static final String TAG = DirGalleryProvider.class.getSimpleName();

    private static final int INVALID_INDEX = -1;

    private final Resources mResources;
    private final UniFile mDir;
    private final Stack<Integer> mRequests = new Stack<>();
    private final AtomicInteger mDecodingIndex = new AtomicInteger(INVALID_INDEX);
    @Nullable
    private Thread mBgThread;
    private volatile int mSize = STATE_WAIT;
    private String mError;

    public DirGalleryProvider(@NonNull Resources resources, @NonNull UniFile dir) {
        mResources = resources;
        mDir = dir;
    }

    @Override
    public void start() {
        super.start();

        mBgThread = new PriorityThread(this, TAG, Process.THREAD_PRIORITY_BACKGROUND);
        mBgThread.start();
    }

    @Override
    public void stop() {
        super.stop();

        if (mBgThread != null) {
            mBgThread.interrupt();
            mBgThread = null;
        }
    }

    @Override
    public int getChapterCount() {
        return 1;
    }

    @Override
    public int getPageCount(int chapter) {
        return mSize;
    }

    @Override
    protected void onRequestChapter(int chapter) {}

    @Override
    protected void onRequest(int chapter, int index) {
        synchronized (mRequests) {
            if (!mRequests.contains(index) && index != mDecodingIndex.get()) {
                mRequests.add(index);
                mRequests.notify();
            }
        }
        notifyPageWait(chapter, index);
    }

    @Override
    protected void onForceRequest(int chapter, int index) {
        onRequest(chapter, index);
    }

    @Override
    public void onCancelRequest(int chapter, int index) {
        synchronized (mRequests) {
            mRequests.remove(Integer.valueOf(index));
        }
    }

    @Override
    public String getError() {
        return mError;
    }

    @Nullable
    @Override
    public String getError(int chapter) {
        return mError;
    }

    @Override
    public void run() {
        // It may take a long time, so run it in new thread
        final UniFile[] files = mDir.listFiles(new Filter());

        if (files == null) {
            mSize = STATE_ERROR;
            mError = mResources.getString(R.string.invalid_path);

            // Notify to to show error
            notifyChapterStateChanged(0);
            return;
        }

        // Sort it
        Arrays.sort(files, new UniFileComparator());

        // Set state normal and notify
        mSize = files.length;
        notifyStateChanged();

        // Check empty
        if (files.length == 0) {
            return;
        }

        while (!Thread.currentThread().isInterrupted()) {
            final int index;
            synchronized (mRequests) {
                if (mRequests.isEmpty()) {
                    try {
                        mRequests.wait();
                    } catch (InterruptedException e) {
                        // Interrupted
                        break;
                    }
                    continue;
                }
                index = mRequests.pop();
                mDecodingIndex.lazySet(index);
            }

            // Check index valid
            if (index < 0 || index >= files.length) {
                mDecodingIndex.lazySet(INVALID_INDEX);
                notifyPageFailed(0, index, mResources.getString(R.string.out_of_range));
                continue;
            }

            InputStream is = null;
            try {
                is = files[index].openInputStream();
                final ImageData image = Image.decode(is, false);
                mDecodingIndex.lazySet(INVALID_INDEX);
                if (image != null) {
                    notifyPageSucceed(0, index, image);
                } else {
                    notifyPageFailed(0, index, mResources.getString(R.string.decoding_failed));
                }
            } catch (IOException e) {
                mDecodingIndex.lazySet(INVALID_INDEX);
                notifyPageFailed(0, index, mResources.getString(R.string.reading_failed));
            } finally {
                IOUtils.closeQuietly(is);
            }
            mDecodingIndex.lazySet(INVALID_INDEX);
        }
    }


    private static class Filter implements FilenameFilter {

        private static final String[] ACCEPTED_EXTENSIONS = {
                "jpg",
                "jpeg",
                "png",
                "gif",
        };

        @Override
        public boolean accept(UniFile dir, String filename) {
            return filename != null && StringUtils.endsWith(filename, ACCEPTED_EXTENSIONS) != null;
        }
    }

    private static class UniFileComparator implements Comparator<UniFile> {
        @Override
        public int compare(UniFile lhs, UniFile rhs) {
            final String lhsName = lhs.getName();
            final String rhsName = rhs.getName();
            if (lhsName != null) {
                if (rhsName != null) {
                    return lhsName.compareTo(rhsName);
                } else {
                    return 1;
                }
            } else {
                if (rhsName != null) {
                    return -1;
                } else {
                    return 0;
                }
            }
        }
    }
}
