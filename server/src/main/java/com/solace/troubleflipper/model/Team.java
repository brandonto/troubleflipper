package com.solace.troubleflipper.model;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.*;

public class Team {

    private String id;
    private String name;
    private List<String> puzzleNames;
    @JsonIgnore
    private Map<CharacterType, Player> characters = new EnumMap<>(CharacterType.class);
    @JsonIgnore
    private Map<String, Player> playersMap = new HashMap<>();
    private List<Player> players = new ArrayList<>();
    @JsonIgnore
    private Game game;
    private int completedGames = 0;
    private boolean immune = false;

    public Team() {
        id = UUID.randomUUID().toString();
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public void setGame (Game game) {
        this.game=game;
    }

    public Game getGame() {
        return game;
    }

    public List<String> getPuzzleNames() {
        return puzzleNames;
    }

    public void setPuzzleNames(List<String> puzzleNames) {
        this.puzzleNames = puzzleNames;
    }

    public String chooseNextPuzzleName() {
        if (this.puzzleNames == null || this.puzzleNames.isEmpty()) {
            return null;
        } else if (this.puzzleNames.size() == 1) {
            return this.puzzleNames.get(0);
        } else {
            String puzzleName = this.puzzleNames.remove(0);
            this.puzzleNames.add(puzzleName);
            return puzzleName;
        }
    }

    public void addPlayer(Player player) {
        playersMap.put(player.getClientName(), player);
        players.add(player);
        player.setTeam(this);
    }

    public void removePlayer(Player player) {
        playersMap.remove(player.getClientName());
        players.remove(player);
        player.setTeam(null);
    }

    public void chooseCharacter(CharacterType characterType, Player player) {
        Character character = createCharacter(characterType);
        player.setCharacter(character);
        characters.put(characterType, player);
    }

    public void addBonusCharacter(CharacterType characterType, Player player) {
        if (!characters.containsKey(characterType) && !player.getBonusCharacters().containsKey(characterType)) {
            Character character = createCharacter(characterType);
            player.getBonusCharacters().put(characterType, character);
            characters.put(characterType, player);
        }
    }

    public Player getPlayer(String clientName) {
        return playersMap.get(clientName);
    }

    public Player getPlayer(CharacterType characterType) {
        return characters.get(characterType);
    }

    public List<Player> getPlayers() {
        return players;
    }

    public void addCompletedGame() {
        completedGames++;
    }

    public int getCompletedGames() {
        return completedGames;
    }

    private Character createCharacter(CharacterType characterType) {
        Character character;
        switch (characterType) {
            case peach:
                character = new Peach();
                break;
            case bowser:
                character = new Bowser();
                break;
            case yoshi:
                character = new Yoshi();
                break;
            case goomba:
                character = new Goomba();
                break;
            default:
                character = new Mario();
                break;
        }
        return character;
    }

    public boolean isImmune() {
        return immune;
    }

    public void setImmune(boolean immune) {
        this.immune = immune;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        Team team = (Team) o;

        return id.equals(team.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }
}
