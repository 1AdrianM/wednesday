package com.github.wednesday.ai;

import com.github.wednesday.common.ModelRequest;
import com.github.wednesday.common.StreamHandle;
import com.github.wednesday.common.StreamListener;

/** Streaming text generation with tool-call events. */
public interface LanguageModel {

    StreamHandle generate(ModelRequest request, StreamListener<ModelEvent> listener);
}
