package com.solace.troubleflipper.messages;

import com.solace.troubleflipper.model.CharacterType;

public class PeachHealMessage {

    private CharacterType characterType;

    public CharacterType getCharacterType() {
        return characterType;
    }

    public void setCharacterType(CharacterType characterType) {
        this.characterType = characterType;
    }
}
