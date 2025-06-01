package rlbotexample;

import rlbot.Bot;
import rlbot.ControllerState;
import rlbot.flat.GameTickPacket;
import rlbot.flat.ScoreInfo;
import rlbotexample.input.DataPacket;
import rlbotexample.input.car.CarData;
import rlbotexample.output.ControlsOutput;
import rlbotexample.vector.Vector2;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;

public class ScimecaBot implements Bot {

    private final int playerindex;
    private final String filepath;

    private int game_reads = 0;
    private boolean stats_saved = false;
    private int kickofftickr = 0;
    private int shotofftickr = 0;

    public ScimecaBot(int playerIndex) {
        this.playerindex = playerIndex;
        this.filepath = "C:/Users/Skavv/Downloads/RLBotJavaExample-master/RLBotJavaExample-master/src/main/scimeca_stats_"
                + new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date()) + ".txt";
        try {
            FileWriter initwriter = new FileWriter(filepath, false);
            initwriter.write("ScimecaBot Log Start\n");
            initwriter.close();
        } catch (IOException e) {
            System.err.println("[ScimecaBot] ERROR initializing stats file: " + e.getMessage());
        }
    }

    private void logPacket(GameTickPacket packet) {
        try (FileWriter writer = new FileWriter(filepath, true)) {
            writer.write("Tick: " + game_reads + " | ");
            if (packet.playersLength() > playerindex) {
                ScoreInfo score = packet.players(playerindex).scoreInfo();
                writer.write("Goals=" + score.goals() + ", Shots=" + score.shots() + ", ");
            }
            writer.write("Ball Pos=(" + packet.ball().physics().location().x() + ", " +
                    packet.ball().physics().location().y() + ")\n");
        } catch (IOException e) {
            System.err.println("[ScimecaBot] ERROR logging packet: " + e.getMessage());
        }
    }

    private void savePGS(GameTickPacket packet) {
        if (stats_saved) return;
        stats_saved = true;
        File statfile = new File(filepath);
        try (FileWriter writer = new FileWriter(statfile, true)) {
            writer.write("\n--- Init Stats ---\n");
            writer.write("Inputs Processed: " + game_reads + "\n");
            int blueGoals = 0, orangeGoals = 0;
            writer.write("Player Stats:\n");
            for (int i = 0; i < packet.playersLength(); i++) {
                ScoreInfo s = packet.players(i).scoreInfo();
                String teamColor = packet.players(i).team() == 0 ? "Blue" : "Orange";
                writer.write("  [" + teamColor + "] Player " + i + ": ");
                writer.write("Goals=" + s.goals() + ", Assists=" + s.assists() + ", Saves=" + s.saves() + ", Shots=" + s.shots() + ", Demos=" + s.demolitions() + "\n");
                if (packet.players(i).team() == 0) blueGoals += s.goals();
                else orangeGoals += s.goals();
            }
            writer.write("\nTeam Totals:\n");
            writer.write("  Blue Goals: " + blueGoals + "\n");
            writer.write("  Orange Goals: " + orangeGoals + "\n");
            String result;
            if (blueGoals > orangeGoals) result = "Blue Team Wins";
            else if (orangeGoals > blueGoals) result = "Orange Team Wins";
            else result = "Draw";
            writer.write("\nResult: " + result + "\n");
        } catch (IOException e) {
            System.err.println("[ScimecaBot] ERROR writing stats file: " + e.getMessage());
        }
    }

    private ControlsOutput processInputLogic(DataPacket input, boolean isKickoff) {
        CarData car = input.car;
        Vector2 carpos = car.position.flatten();
        Vector2 throttledirection = car.orientation.noseVector.flatten();
        Vector2 ballpos = input.ball.position.flatten();
        Vector2 cardist_to_ball = ballpos.minus(carpos);
        double angle_to_ball = throttledirection.correctionAngle(cardist_to_ball);
        double angle_to_ball_abs = Math.abs(angle_to_ball);
        boolean shouldBoost = angle_to_ball_abs < Math.toRadians(10) && car.boost > 0 && car.velocity.magnitude() < 2200;
        boolean car_flip = false;
        boolean shouldPowerslide = angle_to_ball_abs > Math.toRadians(35);
        Vector2 targetgoal = (car.team == 0) ? new Vector2(0, 5120) : new Vector2(0, -5120);
        Vector2 balltogoal = targetgoal.minus(ballpos);
        Vector2 approach_offset = balltogoal.normalized().scaled(130);
        Vector2 targetpos = ballpos.minus(approach_offset);
        Vector2 car_to_target = targetpos.minus(carpos);
        double steercorrec = throttledirection.correctionAngle(car_to_target);
        boolean should_go_left = steercorrec > 0;

        if (Math.abs(car.position.z) > 300 && car.velocity.magnitude() < 100) {
            return new ControlsOutput().withThrottle(-1).withSlide(true);
        }

        if (isKickoff) {
            kickofftickr++;
            if (kickofftickr == 10) {
                return new ControlsOutput().withThrottle(1).withJump(true).withPitch(-1);
            } else if (kickofftickr > 10 && kickofftickr < 25) {
                return new ControlsOutput().withThrottle(1).withBoost(true);
            } else {
                return new ControlsOutput().withThrottle(1).withBoost(true).withSteer(should_go_left ? -1 : 1);
            }
        } else {
            kickofftickr = 0;
        }

        if (shotofftickr == 0 && angle_to_ball_abs < Math.toRadians(8) && cardist_to_ball.magnitude() < 650 && car.hasWheelContact && !car.isSupersonic) {
            car_flip = true;
            shotofftickr = 60;
        }

        if (shotofftickr > 0) {
            shotofftickr--;
        }

        return new ControlsOutput()
                .withSteer(should_go_left ? -1 : 1)
                .withThrottle(1)
                .withBoost(shouldBoost)
                .withJump(car_flip)
                .withSlide(shouldPowerslide);
    }

    @Override
    public int getIndex() {
        return this.playerindex;
    }

    @Override
    public ControllerState processInput(GameTickPacket packet) {
        game_reads++;
        if (packet.playersLength() <= playerindex || packet.ball() == null) {
            return new ControlsOutput();
        }
        logPacket(packet);
        if (!packet.gameInfo().isRoundActive()) {
            savePGS(packet);
        }
        boolean kickoff = packet.gameInfo().isKickoffPause();
        DataPacket dp = new DataPacket(packet, playerindex);
        return processInputLogic(dp, kickoff);
    }

    @Override
    public void retire() {
        System.out.println("[ScimecaBot] Retiring bot #" + playerindex);
    }
}
