package com.github.wednesday.ai;

import com.github.wednesday.tts.provider.archfixture.FixtureVendorClient;

/** Test fixture: an AI class that wrongly reaches into a TTS provider package. */
public class ArchFixtureLeak {
    void leak() {
        new FixtureVendorClient().speak();
    }
}
