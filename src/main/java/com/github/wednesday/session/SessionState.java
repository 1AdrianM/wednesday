package com.github.wednesday.session;

/** Where a session is in the conversation. Allowed moves live in {@link SessionStateMachine}. */
public enum SessionState {
    /** Waiting for the user. */
    IDLE,
    /** Receiving the user's speech. */
    LISTENING,
    /** The user's turn is complete; waiting for the reply to start sounding. */
    THINKING,
    /** Wednesday's voice is playing. */
    SPEAKING
}
