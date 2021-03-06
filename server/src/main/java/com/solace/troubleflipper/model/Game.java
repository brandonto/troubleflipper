package com.solace.troubleflipper.model;

import com.solace.troubleflipper.GameOverListener;
import com.solace.troubleflipper.Publisher;
import com.solace.troubleflipper.Subscriber;
import com.solace.troubleflipper.messages.*;
import com.solace.troubleflipper.properties.BadGuyActionHandler;
import com.solace.troubleflipper.properties.TournamentProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

public class Game {

    private Logger log = LoggerFactory.getLogger("game");

    private final List<PuzzlePiece> puzzleBoard = new ArrayList<>();
    private String puzzleName;
    private Team team;

    private Subscriber subscriber;
    private Publisher publisher;
    private Timer timer;
    private final TournamentProperties tournamentProperties;
    private final BadGuyActionHandler badGuyActionHandler;

    // game won
    private boolean gameOver = false;

    // game stopped
    private volatile boolean gameStopped = false;

    private int correctPieces;

    private final Collection<GameOverListener> gameOverListeners = new ArrayList<>();

    public Game(Team team, Subscriber subscriber, Publisher publisher, Timer timer,
                TournamentProperties tournamentProperties, BadGuyActionHandler badGuyActionHandler) {
        this.team = team;
        this.subscriber = subscriber;
        this.publisher = publisher;
        this.timer = timer;
        this.tournamentProperties = tournamentProperties;
        this.badGuyActionHandler = badGuyActionHandler;
        subscriber.registerHandler(SwapPiecesMessage.class, "games/" + team.getId(), this::swapPieces);
        subscriber.registerHandler(SelectPieceMessage.class, "games/" + team.getId() + "/selectPiece", this::selectPiece);
        subscriber.registerHandler(ResetPieceMessage.class, "games/" + team.getId() + "/resetPiece", this::resetPiece);
        subscriber.registerHandler(PickCharacterMessage.class, "games/" + team.getId() + "/pickCharacter", this::pickCharacterHandler);
        subscriber.registerHandler(StarPowerMessage.class, "games/" + team.getId() + "/starPower", this::starPowerHandler);
        subscriber.registerHandler(PeachHealMessage.class, "games/" + team.getId() + "/peachHeal", this::peachHealHandler);
        subscriber.registerHandler("games/" + team.getId() + "/yoshiGuard", this::yoshiGuardHandler);
        subscriber.registerHandler("games/" + team.getId() + "/troubleFlipper", this::troubleFlipperHandler);
        subscriber.registerHandler("games/" + team.getId() + "/greenShell", this::greenShellHandler);
        subscriber.registerHandler("games/" + team.getId() + "/queryGame", this::queryGameHandler);
    }

    public boolean isGameOver() {
        return gameOver;
    }

    public void addGameOverListener(GameOverListener gameOverListener) {
        this.gameOverListeners.add(gameOverListener);
    }

    public void clearGameOverListeners() {
        this.gameOverListeners.clear();
    }


    public Team getTeam() {
        return team;
    }

    public String getPuzzleName() {
        return puzzleName;
    }

    public void setPuzzleName(String puzzleName) {
        this.puzzleName = puzzleName;
    }

    private void swapPieces(PuzzlePiece piece1, PuzzlePiece piece2, Player player) {
        if (player != null) {
            log.debug("Update player stats");
            int piece1Position = puzzleBoard.indexOf(piece1);
            int piece2Position = puzzleBoard.indexOf(piece2);
            if (piece1Position == piece1.getIndex()) {
                player.wrongMove();
            }
            if (piece2Position == piece2.getIndex()) {
                player.wrongMove();
            }
            if (piece1Position == piece2.getIndex()) {
                player.rightMove();
            }
            if (piece2Position == piece1.getIndex()) {
                player.rightMove();
            }
        }
        synchronized (puzzleBoard) {
            if (gameOver) {
                return;
            }
            try {
                PuzzlePiece bPiece1 = findPuzzlePiece(piece1.getIndex());
                PuzzlePiece bPiece2 = findPuzzlePiece(piece2.getIndex());
                bPiece1.setIndex(piece2.getIndex());
                bPiece2.setIndex(piece1.getIndex());
                bPiece1.setSelectedBy("");
                bPiece2.setSelectedBy("");
                bPiece1.setLastSelectTimestamp(-1);
                bPiece2.setLastSelectTimestamp(-1);
            } catch (NoPieceFoundException ex) {
                log.error("Unable to swap pieces " + piece1.getIndex() + " and " + piece2.getIndex(), ex);
            }
        }
    }

    private void selectPiece(PuzzlePiece piece, Player player) {
        synchronized (puzzleBoard) {
            try {
                PuzzlePiece bPiece = findPuzzlePiece(piece.getIndex());
                String oldSelectedBy = bPiece.getSelectedBy();
                String newSelectedBy = piece.getSelectedBy();
//                log.info(piece.getIndex() + ", oldSelectedBy = " + oldSelectedBy + ", newSelectedBy = " + newSelectedBy +
//                        ", player.getClientName() = " + player.getClientName() + ", player.getGamerTag() = " + player.getGamerTag());

                if (oldSelectedBy.equals("") && !newSelectedBy.equals("")) {
                    if (team.getPlayer(newSelectedBy) != null) {
                        bPiece.setSelectedBy(newSelectedBy);
                        bPiece.setLastSelectTimestamp(System.currentTimeMillis());
                    }
                } else if (!oldSelectedBy.equals("") && newSelectedBy.equals("")) {
                    if (oldSelectedBy.equals(player.getClientName())) {
                        bPiece.setSelectedBy("");
                        bPiece.setLastSelectTimestamp(-1);
                    }
                }
            } catch (NoPieceFoundException ex) {
                log.error("Unable to select piece " + piece.getIndex(), ex);
            }
        }
    }

    private PuzzlePiece findPuzzlePiece(int index) throws NoPieceFoundException {
        for (PuzzlePiece puzzlePiece : puzzleBoard) {
            if (puzzlePiece.getIndex() == index) {
                return puzzlePiece;
            }
        }
        throw new NoPieceFoundException("No pieces found for piece index value: " + index + " for team " + team.getId());
    }

    public void start() {
        synchronized (puzzleBoard) {
            int puzzleLength = tournamentProperties.getPuzzleSize() * tournamentProperties.getPuzzleSize();
            for (int i = 0; i < puzzleLength ; i++) {
                PuzzlePiece puzzlePiece = new PuzzlePiece();
                puzzlePiece.setIndex(i);
                puzzlePiece.setSelectedBy("");
                puzzlePiece.setLastSelectTimestamp(-1);
                puzzleBoard.add(puzzlePiece);
            }
            Collections.shuffle(puzzleBoard);
            timer.schedule(new TimerTask() {
                @Override
                public void run() {
                    synchronized (puzzleBoard) {
                        if (gameOver || gameStopped) {
                            log.info("Cancel deselect checking because game over or game stopped for " + team.getId());
                            this.cancel();
                            return;
                        }

                        boolean puzzleUpdated = false;
                        for (PuzzlePiece puzzlePiece : puzzleBoard) {
                            if (puzzlePiece.getLastSelectTimestamp() == -1) {
                                continue;
                            }

                            //log.info("last = " + puzzlePiece.getLastSelectTimestamp() + ", curr = " + System.currentTimeMillis());
                            if (System.currentTimeMillis() > puzzlePiece.getLastSelectTimestamp() + 10000 ) {
                                //log.info("deselect");
                                puzzleUpdated = true;
                                puzzlePiece.setSelectedBy("");
                                puzzlePiece.setLastSelectTimestamp(-1);
                            }
                        }
                        if (puzzleUpdated) {
                            updatePuzzleForTeam(false);
                        }
                    }
                }
            }, 1000, 1000);
        }
    }

    public void updatePuzzleForTeam(boolean tournamentStopped) {
        boolean won = isGameWon();
        UpdatePuzzleMessage updatePuzzleMessage = new UpdatePuzzleMessage();
        updatePuzzleMessage.setTeamId(team.getId());
        updatePuzzleMessage.setTeamName(team.getName());
        updatePuzzleMessage.setPuzzleName(puzzleName);
        updatePuzzleMessage.setCorrectPieces(correctPieces);
        updatePuzzleMessage.setPuzzle(puzzleBoard);
        updatePuzzleMessage.setGameWon(won);
        updatePuzzleMessage.setCompletedGames(team.getCompletedGames());
        updatePuzzleMessage.setPlayers(team.getPlayers());
        updatePuzzleMessage.setGameOver(tournamentStopped);
        try {
            if (tournamentStopped) {
                log.info("Publish game tournament stopped message to " + team.getId());
            }
            publisher.publish("team/" + team.getId(), updatePuzzleMessage);
        } catch (PublisherException ex) {
            log.error("Unable to update puzzle for team " + team.getId(), ex);
        }
        if (won) {
            log.info("Team " + team.getId() + " won the game!");
            removeGameHandlers();
            gameOverListeners.forEach(l -> l.gameOver(this));
        }
    }

    private void removeGameHandlers() {
        subscriber.deregisterHandler("games/" + team.getId());
        subscriber.deregisterHandler("games/" + team.getId() + "/selectPiece");
        subscriber.deregisterHandler("games/" + team.getId() + "/resetPiece");
        subscriber.deregisterHandler("games/" + team.getId() + "/pickCharacter");
        subscriber.deregisterHandler("games/" + team.getId() + "/starPower");
        subscriber.deregisterHandler("games/" + team.getId() + "/peachHeal");
        subscriber.deregisterHandler("games/" + team.getId() + "/yoshiGuard");
        subscriber.deregisterHandler("games/" + team.getId() + "/troubleFlipper");
        subscriber.deregisterHandler("games/" + team.getId() + "/greenShell");
        subscriber.deregisterHandler("games/" + team.getId() + "/queryGame");
    }

    public boolean stop() {
        gameStopped = true;
        removeGameHandlers();
        return isGameWon();
    }

    private boolean isGameWon() {
        synchronized (puzzleBoard) {
            if (puzzleBoard.size() == 0) {
                return false;
            }
            boolean result = true;
            correctPieces = 0;
            for (int i = 0; i < puzzleBoard.size(); ++i) {
                if (puzzleBoard.get(i).getIndex() == i) {
                    correctPieces++;
                } else {
                    result = false;
                }
            }
            if (result) {
                gameOver = true;
            }
            return result;
        }
    }

    public int getCorrectPieces() {
        return correctPieces;
    }

    private void swapPieces(SwapPiecesMessage swapPiecesMessage) {
        if (gameStopped) {
            return;
        }
        if (swapPiecesMessage.getPiece1().getIndex() == swapPiecesMessage.getPiece2().getIndex()) {
            return;
        }
        Player player = team.getPlayer(swapPiecesMessage.getClientId());
        swapPieces(swapPiecesMessage.getPiece1(), swapPiecesMessage.getPiece2(), player);
        updatePuzzleForTeam(false);
    }

    private void selectPiece(SelectPieceMessage selectPieceMessage) {
        if (gameStopped) {
            return;
        }
        Player player = team.getPlayer(selectPieceMessage.getClientId());
        selectPiece(selectPieceMessage.getPiece(), player);
        updatePuzzleForTeam(false);
    }

    private void resetPiece(ResetPieceMessage resetPieceMessage) {
        if (gameStopped) {
            return;
        }
        synchronized (puzzleBoard) {
            try {
                PuzzlePiece piece = resetPieceMessage.getPiece();
                PuzzlePiece bPiece = findPuzzlePiece(piece.getIndex());
                if (bPiece.getSelectedBy() == null || bPiece.getSelectedBy().equals("")) {
                    bPiece.setLastSelectTimestamp(-1L);
                } else {
                    if (team.getPlayer(bPiece.getSelectedBy()) == null) {
                        bPiece.setSelectedBy("");
                        bPiece.setLastSelectTimestamp(-1L);
                    } else if (bPiece.getLastSelectTimestamp() > 0 && System.currentTimeMillis() - bPiece.getLastSelectTimestamp() >= 10000) {
                        bPiece.setSelectedBy("");
                        bPiece.setLastSelectTimestamp(-1L);
                    }
                }
                updatePuzzleForTeam(false);
            } catch (NoPieceFoundException ex) {
                log.error("Unable to reset piece " + resetPieceMessage.getPiece().getIndex(), ex);
            }
        }
    }

    private void pickCharacterHandler(PickCharacterMessage pickCharacterMessage) {
        if (gameStopped) {
            return;
        }
        String clientName = pickCharacterMessage.getClientId();
        CharacterType characterType = pickCharacterMessage.getCharacterType();

        if (clientName.equals("")) {
            // specialnextInt message to assign characters randomly
            updateCharactersForTeam(true);
        } else {
            Player player = team.getPlayer(characterType);
            if (player == null) {
                player = team.getPlayer(clientName);
                if (player != null && player.getCharacter() == null) {
                    team.chooseCharacter(characterType, player);
                }
            }
            updateCharactersForTeam(false);
        }
    }

    public boolean isCharacterReadyForTeam() {
        List<CharacterType> availableTypes = new ArrayList<>();
        for (CharacterType characterType : CharacterType.values()) {
            if (team.getPlayer(characterType) == null) {
                availableTypes.add(characterType);
            }
        }
        if (team.getPlayers().size() == 1) {
            availableTypes.remove(CharacterType.peach);
        }
        return availableTypes.isEmpty();
    }

    public void updateCharactersForTeam(boolean forceAssign) {
        List<CharacterType> availableTypes = new ArrayList<>();
        for (CharacterType characterType : CharacterType.values()) {
            if (team.getPlayer(characterType) == null) {
                availableTypes.add(characterType);
            }
        }
        UpdateCharacterMessage updateCharacterMessage = new UpdateCharacterMessage();
        updateCharacterMessage.setTeamId(team.getId());
        updateCharacterMessage.setTeamName(team.getName());
        List<Player> players = team.getPlayers();
        if (players.size() == 1) {
            // remove peach from available types because one player team cannot heal himself
            availableTypes.remove(CharacterType.peach);
        }
        if (forceAssign && availableTypes.size() > 0) {
            log.debug("Force assign characters");
            players.forEach(player -> {
                if (player.getCharacter() == null && availableTypes.size() > 0) {
                    team.chooseCharacter(availableTypes.remove(0), player);
                }
            });
        }
        // check if all players have characters and whether need to add bonus characters
        if ((players.size() == 1 && availableTypes.size() == (tournamentProperties.getPlayersPerTeam() - 2)) ||
                (players.size() > 1 && players.size() < tournamentProperties.getPlayersPerTeam() &&
                availableTypes.size() == (tournamentProperties.getPlayersPerTeam() - players.size()))) {
            log.debug("Adding bonus characters");
            Random randomGen = new Random();
            for (Iterator<CharacterType> it = availableTypes.iterator(); it.hasNext();) {
                CharacterType characterType = it.next();
                if (players.size() == 1) {
                    Player player = players.get(0);
                    team.addBonusCharacter(characterType, player);
                } else {
                    int playerIndex = randomGen.nextInt(players.size());
                    team.addBonusCharacter(characterType, players.get(playerIndex));
                }
                it.remove();
            }
        }
        if (!forceAssign) {
            updateCharacterMessage.setAvailableCharacters(availableTypes);
        }
        updateCharacterMessage.setPlayers(team.getPlayers());

        try {
            publisher.publish("team/" + team.getId(), updateCharacterMessage);
        } catch (PublisherException ex) {
            log.error("Unable to update character for team " + team.getId(), ex);
        }
    }

    private void starPowerHandler(StarPowerMessage starPowerMessage) {
        if (gameStopped) {
            return;
        }
        log.info("Got starPower");
        Player player = getTeam().getPlayer(CharacterType.mario);
        if (player != null) {
            Mario mario;
            if (player.getCharacter() instanceof Mario) {
                mario = (Mario) player.getCharacter();
            } else {
                mario = (Mario) player.getBonusCharacters().get(CharacterType.mario);
            }
            if (mario.getSuperPower() > 0) {
                log.debug("Mario used star power");
                mario.useSuperPower();
                starPower(starPowerMessage.getPuzzlePiece());
                updatePuzzleForTeam(false);
            }
        } else {
            log.info("Cannot find mario player");
        }
    }

    private void peachHealHandler(PeachHealMessage peachHealMessage) {
        if (gameStopped) {
            return;
        }
        log.info("Got peachHeal");
        Player peachPlayer = getTeam().getPlayer(CharacterType.peach);
        if (peachPlayer != null) {
            CharacterType characterType = peachHealMessage.getCharacterType();
            Player player = getTeam().getPlayer(characterType);
            if (player != null) {
                Peach peach;
                if (peachPlayer.getCharacter() instanceof Peach) {
                    peach = (Peach) peachPlayer.getCharacter();
                } else {
                    peach = (Peach) peachPlayer.getBonusCharacters().get(CharacterType.peach);
                }
                if (peach.getSuperPower() > 0) {
                    peach.useSuperPower();
                    if (player.getCharacter().getType() == characterType) {
                        player.getCharacter().heal();
                    } else if (player.getBonusCharacters().get(characterType) != null){
                        player.getBonusCharacters().get(characterType).heal();
                    }
                    updatePuzzleForTeam(false);
                }
            } else {
                log.info("Cannot find player to heal");
            }
        } else {
            log.info("Cannot find peach player");
        }

//    }
    }

    private void yoshiGuardHandler() {
        if (gameStopped) {
            return;
        }
        Player player = getTeam().getPlayer(CharacterType.yoshi);
        if (player != null) {
            Yoshi yoshi;
            if (player.getCharacter() instanceof Yoshi) {
                yoshi = (Yoshi) player.getCharacter();
            } else {
                yoshi = (Yoshi) player.getBonusCharacters().get(CharacterType.yoshi);
            }
            if (yoshi.getSuperPower() > 0) {
                yoshi.useSuperPower();
                team.setImmune(true);
                log.info("Team " + team.getName() + " is protected by Yoshi Guard for the next 10 seconds");
                timer.schedule(new TimerTask() {
                    @Override
                    public void run() {
                        team.setImmune(false);
                        log.info("Team " + team.getName() + " is no longer protected by Yoshi Guard");
                    }
                }, 10000);
                updatePuzzleForTeam(false);
            }
        } else {
            log.info("Cannot find yoshi player");
        }
    }

    private void troubleFlipperHandler() {
        if (gameStopped) {
            return;
        }
        Player player = getTeam().getPlayer(CharacterType.bowser);
        if (player != null) {
            Bowser bowser;
            if (player.getCharacter() instanceof Bowser) {
                bowser = (Bowser) player.getCharacter();
            } else {
                bowser = (Bowser) player.getBonusCharacters().get(CharacterType.bowser);
            }
            if (bowser.getSuperPower() > 0) {
                bowser.useSuperPower();
                badGuyActionHandler.troubleFlipper(player);
            }
        } else {
            log.info("Cannot find bowser player");
        }
    }

    private void greenShellHandler() {
        if (gameStopped) {
            return;
        }
        Player player = getTeam().getPlayer(CharacterType.goomba);
        if (player != null) {
            Goomba goomba;
            if (player.getCharacter() instanceof Goomba) {
                goomba = (Goomba) player.getCharacter();
            } else {
                goomba = (Goomba) player.getBonusCharacters().get(CharacterType.goomba);
            }
            if (goomba.getSuperPower() > 0) {
                goomba.useSuperPower();
                badGuyActionHandler.greenShell(player);
            }
        } else {
            log.info("Cannot find goomba player");
        }
    }

    private void queryGameHandler() {
        if (gameStopped) {
            return;
        }
        updatePuzzleForTeam(false);
    }

    private void starPower(PuzzlePiece selectedPuzzlePiece) {
        if (gameStopped) {
            return;
        }
        int correctIndexForPuzzlePiece = selectedPuzzlePiece.getIndex();
        Player mario = team.getPlayer(CharacterType.mario);
        if (mario != null) {
            swapPieces(selectedPuzzlePiece, puzzleBoard.get(correctIndexForPuzzlePiece), mario);
        } else {
            log.info("Cannot find mario player");
        }
    }

    public void troubleFlipper(Player bowser) {
        if (gameStopped) {
            return;
        }
        if (!gameOver && !team.isImmune()) {
            log.info(bowser.getGamerTag() + " from team " + bowser.getTeam().getName()+  " used trouble flipper on " + team.getName());
            synchronized (puzzleBoard) {
                Collections.shuffle(puzzleBoard);
            }
            updatePuzzleForTeam(false);
        } else if (team.isImmune()) {
            log.info("Team " + team.getName() + " has yoshi guarded a trouble flipper attack from " + bowser.getGamerTag() + " on team " + bowser.getTeam().getName());
        }
    }

    public void greenShell() {
        if (gameStopped) {
            return;
        }
        if (!gameOver && !team.isImmune()) {
            List<PuzzlePiece> correctPieces = new ArrayList<>();
            synchronized (puzzleBoard) {
                for (int i = 0; i < puzzleBoard.size(); ++i) {
                    PuzzlePiece puzzlePiece = puzzleBoard.get(i);
                    if (puzzlePiece.getIndex() == i) {
                        PuzzlePiece newPiece = new PuzzlePiece();
                        newPiece.setIndex(puzzlePiece.getIndex());
                        newPiece.setSelectedBy(puzzlePiece.getSelectedBy());
                        correctPieces.add(newPiece);
                    }
                }
                Collections.shuffle(correctPieces);
            }
            if (correctPieces.size() >= 2) {
                swapPieces(correctPieces.get(0), correctPieces.get(1), null);
            } else if (correctPieces.size() == 1) {
                // TODO should probably swap a single piece with a random piece here
            }
            updatePuzzleForTeam(false);
        } else if (team.isImmune()) {
            log.info("Team " + team.getName() + " has yoshi guarded a green shell attack!");
        }
    }

}
