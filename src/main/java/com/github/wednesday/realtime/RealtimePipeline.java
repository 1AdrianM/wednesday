package com.github.wednesday.realtime;


import org.springframework.stereotype.Service;

import com.github.wednesday.ia.llm.LlmService;
import com.github.wednesday.ia.speech.sts.SpeechToTextService;
import com.github.wednesday.ia.speech.tts.TextToSpeechService;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class RealtimePipeline {
 private final LlmService llmService;;
 private final SpeechToTextService speechToTextService;
 private final TextToSpeechService textToSpeechService;

  String execute(){
     return "";
   }
}
