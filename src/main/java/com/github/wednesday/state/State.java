package com.github.wednesday.state;

import lombok.NoArgsConstructor;

@NoArgsConstructor
public class State {
    private StatesTypes currentState;

    public static State InitialState() {
        State state = new State();
        state.currentState = StatesTypes.IDLE;
        return state;
    }

    public StatesTypes getCurrentState() {
        return currentState;
    }

    public void setCurrentState(StatesTypes currentState) {
        this.currentState = currentState;
    }
}
