/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.android.exoplayer.metadata;

import com.google.android.exoplayer.ExoPlaybackException;
import com.google.android.exoplayer.MediaFormatHolder;
import com.google.android.exoplayer.SampleHolder;
import com.google.android.exoplayer.SampleSource;
import com.google.android.exoplayer.SampleSource.SampleSourceReader;
import com.google.android.exoplayer.TrackRenderer;
import com.google.android.exoplayer.util.Assertions;

import android.os.Handler;
import android.os.Handler.Callback;
import android.os.Looper;
import android.os.Message;

import java.io.IOException;

/**
 * A {@link TrackRenderer} for metadata embedded in a media stream.
 *
 * @param <T> The type of the metadata.
 */
public class MetadataTrackRenderer<T> extends TrackRenderer implements Callback {

  /**
   * An interface for components that process metadata.
   *
   * @param <T> The type of the metadata.
   */
  public interface MetadataRenderer<T> {

    /**
     * Invoked each time there is a metadata associated with current playback time.
     *
     * @param metadata The metadata to process.
     */
    void onMetadata(T metadata);

  }

  private static final int MSG_INVOKE_RENDERER = 0;

  private final SampleSourceReader source;
  private final MetadataParser<T> metadataParser;
  private final MetadataRenderer<T> metadataRenderer;
  private final Handler metadataHandler;
  private final MediaFormatHolder formatHolder;
  private final SampleHolder sampleHolder;

  private int trackIndex;
  private boolean inputStreamEnded;

  private long pendingMetadataTimestamp;
  private T pendingMetadata;

  /**
   * @param source A source from which samples containing metadata can be read.
   * @param metadataParser A parser for parsing the metadata.
   * @param metadataRenderer The metadata renderer to receive the parsed metadata.
   * @param metadataRendererLooper The looper associated with the thread on which metadataRenderer
   *     should be invoked. If the renderer makes use of standard Android UI components, then this
   *     should normally be the looper associated with the applications' main thread, which can be
   *     obtained using {@link android.app.Activity#getMainLooper()}. Null may be passed if the
   *     renderer should be invoked directly on the player's internal rendering thread.
   */
  public MetadataTrackRenderer(SampleSource source, MetadataParser<T> metadataParser,
      MetadataRenderer<T> metadataRenderer, Looper metadataRendererLooper) {
    this.source = source.register();
    this.metadataParser = Assertions.checkNotNull(metadataParser);
    this.metadataRenderer = Assertions.checkNotNull(metadataRenderer);
    this.metadataHandler = metadataRendererLooper == null ? null
        : new Handler(metadataRendererLooper, this);
    formatHolder = new MediaFormatHolder();
    sampleHolder = new SampleHolder(SampleHolder.BUFFER_REPLACEMENT_MODE_NORMAL);
  }

  @Override
  protected int doPrepare(long positionUs) throws ExoPlaybackException {
    try {
      boolean sourcePrepared = source.prepare(positionUs);
      if (!sourcePrepared) {
        return TrackRenderer.STATE_UNPREPARED;
      }
    } catch (IOException e) {
      throw new ExoPlaybackException(e);
    }
    for (int i = 0; i < source.getTrackCount(); i++) {
      if (metadataParser.canParse(source.getTrackInfo(i).mimeType)) {
        trackIndex = i;
        return TrackRenderer.STATE_PREPARED;
      }
    }
    return TrackRenderer.STATE_IGNORE;
  }

  @Override
  protected void onEnabled(long positionUs, boolean joining) {
    source.enable(trackIndex, positionUs);
    seekToInternal();
  }

  @Override
  protected void seekTo(long positionUs) throws ExoPlaybackException {
    source.seekToUs(positionUs);
    seekToInternal();
  }

  private void seekToInternal() {
    pendingMetadata = null;
    inputStreamEnded = false;
  }

  @Override
  protected void doSomeWork(long positionUs, long elapsedRealtimeUs)
      throws ExoPlaybackException {
    try {
      source.continueBuffering(trackIndex, positionUs);
    } catch (IOException e) {
      // TODO: This should be propagated, but in the current design propagation may occur too
      // early. See [Internal b/22291244].
      // throw new ExoPlaybackException(e);
    }

    if (!inputStreamEnded && pendingMetadata == null) {
      try {
        int result = source.readData(trackIndex, positionUs, formatHolder, sampleHolder, false);
        if (result == SampleSource.SAMPLE_READ) {
          pendingMetadataTimestamp = sampleHolder.timeUs;
          pendingMetadata = metadataParser.parse(sampleHolder.data.array(), sampleHolder.size);
          sampleHolder.data.clear();
        } else if (result == SampleSource.END_OF_STREAM) {
          inputStreamEnded = true;
        }
      } catch (IOException e) {
        // TODO: This should be propagated, but in the current design propagation may occur too
        // early. See [Internal b/22291244].
        // throw new ExoPlaybackException(e);
      }
    }

    if (pendingMetadata != null && pendingMetadataTimestamp <= positionUs) {
      invokeRenderer(pendingMetadata);
      pendingMetadata = null;
    }
  }

  @Override
  protected void onDisabled() {
    pendingMetadata = null;
    source.disable(trackIndex);
  }

  @Override
  protected long getDurationUs() {
    return source.getTrackInfo(trackIndex).durationUs;
  }

  @Override
  protected long getBufferedPositionUs() {
    return TrackRenderer.END_OF_TRACK_US;
  }

  @Override
  protected boolean isEnded() {
    return inputStreamEnded;
  }

  @Override
  protected boolean isReady() {
    return true;
  }

  private void invokeRenderer(T metadata) {
    if (metadataHandler != null) {
      metadataHandler.obtainMessage(MSG_INVOKE_RENDERER, metadata).sendToTarget();
    } else {
      invokeRendererInternal(metadata);
    }
  }

  @SuppressWarnings("unchecked")
  @Override
  public boolean handleMessage(Message msg) {
    switch (msg.what) {
      case MSG_INVOKE_RENDERER:
        invokeRendererInternal((T) msg.obj);
        return true;
    }
    return false;
  }

  private void invokeRendererInternal(T metadata) {
    metadataRenderer.onMetadata(metadata);
  }

}
