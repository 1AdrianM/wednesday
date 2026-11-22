package com.github.wednesday.tts;

import com.github.wednesday.tts.provider.archfixture.FixtureVendorClient;

/** Test fixture: a TTS class using its own component's provider type, which is allowed. */
public class ArchFixtureOwnUse {
    void use() {
        new FixtureVendorClient().speak();
    }
}
