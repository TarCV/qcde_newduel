package zdoom;

import com.github.tarcv.ztest.simulation.Player;
import com.github.tarcv.ztest.simulation.Simulation;
import net.jqwik.api.*;
import net.jqwik.api.stateful.Action;
import net.jqwik.api.stateful.ActionSequence;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

import static com.github.tarcv.ztest.simulation.AcsConstants.BT_MOVELEFT;
import static com.github.tarcv.ztest.simulation.AcsConstants.BT_MOVERIGHT;
import static com.github.tarcv.ztest.simulation.Simulation.CVarTypes.SERVER;
import static com.github.tarcv.ztest.simulation.Simulation.CVarTypes.USER_CUSTOM;
import static net.jqwik.api.Reporting.FALSIFIED;
import static net.jqwik.api.Reporting.GENERATED;
import static org.assertj.core.api.Assertions.assertThat;
import static zdoom.GlobalNEWDUEL_acs.*;
import static zdoom.WorldNEWDUEL_acs.*;

class TestState {
    final Simulation simulation;
    final MapNEWDUEL_acs map;
    private static final AtomicReference<Simulation> lastSimulation = new AtomicReference<>();
    final List<Player> players = Collections.synchronizedList(new ArrayList<>());
    final SimIdle simIdle = new SimIdle();

    TestState() {
        Simulation newSim = new Simulation<MapNEWDUEL_acs.Scripts>(1L);
        lastSimulation.getAndUpdate(prevSim -> {
/*            if (prevSim != null) {
                prevSim.stopEverything();
            }*/
            return newSim;
        });

        zdoom.WorldNEWDUEL_acs.reset();
        MapNEWDUEL_acs newMapSim = new MapNEWDUEL_acs();
        newMapSim.createMainScriptContext(newSim);
        simulation = newSim;
        map = newMapSim;

        simulation.printlnMarked("");
        simulation.printlnMarked("");
        simulation.printlnMarked("NEW SIMULATION");


        this.simulation.registerCVar("sv_duelcountdowntime", SERVER);
        this.simulation.setCVar("sv_duelcountdowntime", 53);

        this.simulation.registerCVar("qcd_champ", USER_CUSTOM);
    }

    class SimIdle implements Predicate<List<String>> {
        private boolean lastTickWasIdle = false;
        private final List<String> idleScripts = Arrays.asList(
                "QCDED_World_Loop",
                "QCDED_Player_Loop",
                "QCDED_WaitForTwoPlayers"
        );

        @Override
        public boolean test(List<String> strings) {
            strings.forEach(thread -> simulation.printlnMarked("* " + thread + " - active"));

            boolean result = strings.isEmpty() || idleScripts.containsAll(strings);
            result = result && (TestState.this.players.size() < 2 || !strings.contains("QCDED_WaitForTwoPlayers"));
            boolean previousWasIdle = lastTickWasIdle;
            lastTickWasIdle = result;

            simulation.printlnMarked("* IDLE: " + (result && previousWasIdle));

            return result && previousWasIdle;
        }
    }
}

class SimulationProperties {
    @Property(reporting = { GENERATED, FALSIFIED }, tries = 10)
    void checkSimulation(@ForAll("sequences") ActionSequence<TestState> actions) {
        actions
                .withInvariant(model -> {
                    try {
                        model.simulation.withTickLock(() -> {
                            MapNEWDUEL_acs.Scripts scripts = model.map.getScripts();
                            assertThat(scripts).isNotNull();

                            assertThat(roundActive).isNotEqualTo(ROUND_ILLEGAL_STATE);

                            if (model.players.isEmpty()) {
                                assertThat(roundActive).isIn(ROUND_INIT, ROUND_PREDRAFT);
                            } else if (model.players.size() != 2) {
                                assertThat(roundActive).isIn(ROUND_INIT, ROUND_PREDRAFT);
                            } else {
                                assertThat(roundActive).isIn(
                                        ROUND_DRAFTING, ROUND_PREDRAFT, ROUND_WARMUP, ROUND_STARTED);
                            }

                            for (int i = model.players.size(); i < 2; i++) {
                                assertThat(model.map.draftReady[i]).isEqualTo(false);
                                assertThat(model.map.pressedUse[i]).isEqualTo(false);

                                for (int j = 0; j < 5; j++) {
                                    assertThat(scripts.getPickedClass(i, j)).isEqualTo(0);
                                    assertThat(scripts.getChampStatus(i, j)).isEqualTo(STATUS_UNUSED);
                                }
                            }

                            for (int i = 0; i < model.players.size(); i++) {
                                if (roundActive >= ROUND_DRAFTING) {
                                    assertThat(i).isIn(scripts.playerNumberForDuelIndex(0), scripts.playerNumberForDuelIndex(1));
                                }

                                Player player = model.players.get(i);
                                int playerClass = player.getPawnClass();
                                for (int duelIndex = 0; duelIndex < 2; duelIndex++) {
                                    if (duelIndex == i) {
                                        int pickedIndex = -1;
                                        for (int k = 0; k < 5; k++) {

                                            if (scripts.getPickedClass(duelIndex, k) == playerClass) {
                                                pickedIndex = k;
                                                break;
                                            }

                                            if (roundActive != ROUND_STARTED) {
                                                assertThat(scripts.getChampStatus(duelIndex, k))
                                                        .isEqualTo(STATUS_UNUSED);

                                            }
                                        }
                                        if (roundActive == ROUND_WARMUP || roundActive == ROUND_STARTED) {
                                            assertThat(pickedIndex >= 0 || player.getHealth() <= 0).isTrue();
                                        }

                                        if (roundActive == ROUND_STARTED && pickedIndex >= 0) {
                                                assertThat(scripts.getChampStatus(duelIndex, pickedIndex))
                                                        .isEqualTo(STATUS_USED);
                                        }
                                    }
                                }
                            }
                            if (roundActive == ROUND_DRAFTING) {
                                assertThat(model.map.whoDraftsNow).isBetween(0, model.players.size() - 1);
                                assertThat(model.map.whoDraftsFirst).isBetween(0, model.players.size() - 1);
                            }

                            model.simulation.printlnMarked("ALL INVARIANTS ARE STILL HELD");
                        });
                    } catch (AssertionError e) {
                        e.printStackTrace();
                        throw e;
                    }
                })
                .run(new TestState());
    }

    @Provide
    Arbitrary<ActionSequence<TestState>> sequences() {
        return Arbitraries.sequences(Arbitraries.oneOf(
                Combinators
                        .combine(
                                Arbitraries.of(false, true),
                                Arbitraries.of(false, true),
                                Arbitraries.integers().between(0, NUMCHAMPS - 1)
                        )
                        .as((isBot, curClass, classIndex) -> new JoinAction(isBot, curClass ? -1 : classIndex)),
                Combinators
                        .combine(
                                Arbitraries.integers().between(0, 1),
                                Arbitraries.of(false, true),
                                Arbitraries.integers().between(0, NUMCHAMPS - 1)
                        )
                        .as((index, curClass, classIndex) -> new RespawnAction(index, curClass ? -1 : classIndex)),
                Combinators
                        .combine(
                                Arbitraries.integers().between(0, 1),
                                Arbitraries.of(false, true)
                        )
                        .as(DraftingNextChampionAction::new),
                Arbitraries.integers().between(0, 1).map(DraftingSelectChampionAction::new),
                Arbitraries.integers().between(0, 1).map(FragAction::new),
                Arbitraries.integers().between(1, 35 * 7).map(WaitAction::new)
        ));
    }
}

class JoinAction implements Action<TestState> {
    private final boolean isBot;
    private final int classIndex;

    JoinAction(boolean isBot, int classIndex) {
        this.isBot = isBot;
        this.classIndex = classIndex;
    }

    @Override
    public String toString() {
        return "JoinAction{" +
                "isBot=" + isBot +
                ", classIndex=" + classIndex +
                '}';
    }

    @Override
    public boolean precondition(TestState model) {
        return model.players.size() < 2;
    }

    @Override
    public TestState run(TestState model) {
        int index = model.players.size() + 1;
        Player player = model.simulation.addPlayer(
                String.format("Player%d", index), 100, 100, isBot);
        if (classIndex > 0) {
            player.setCVar("playerclass", classIndex);
        }
        player.joinGame();
        model.players.add(player);

        model.simulation.runAtLeastTicks(5, model.simIdle);

        return model;
    }

}

class RespawnAction implements Action<TestState> {
    private final int index;
    private final int classIndex;

    RespawnAction(int index, int classIndex) {
        this.index = index;
        this.classIndex = classIndex;
    }

    @Override
    public String toString() {
        return "RespawnAction{" +
                "index=" + index +
                ", classIndex=" + classIndex +
                '}';
    }

    @Override
    public boolean precondition(TestState model) {
        return !model.players.isEmpty()
                && index < model.players.size()
                && model.players.get(index).getHealth() <= 0;
    }

    @Override
    public TestState run(TestState model) {
        Player player = model.players.get(index);
        player.joinGame();
        model.simulation.runAtLeastTicks(5, model.simIdle);

        return model;
    }

}

class DraftingNextChampionAction implements Action<TestState> {
    private final int index;
    private final boolean forward;

    DraftingNextChampionAction(int playerIndex, boolean forward) {
        this.index = playerIndex;
        this.forward = forward;
    }

    @Override
    public boolean precondition(TestState model) {
        return !model.players.isEmpty()
                && index < model.players.size()
                && roundActive == ROUND_DRAFTING
                && !model.players.get(index).isBot()
                && !model.map.draftReady[model.map.getScripts().duelIndexFor(index)];
    }

    @Override
    public TestState run(TestState model) {
        Player player = model.players.get(index);
        int key = forward ? BT_MOVERIGHT : BT_MOVELEFT;
        player.downKey(key);
        model.simulation.runAtLeastTicks(3, model.simIdle);
        player.upKey(key);
        model.simulation.runAtLeastTicks(5, model.simIdle);

        return model;
    }

    @Override
    public String toString() {
        return "DraftingNextChampionAction{" +
                "index=" + index +
                '}';
    }
}

class DraftingSelectChampionAction implements Action<TestState> {
    private final int index;

    DraftingSelectChampionAction(int playerIndex) {
        this.index = playerIndex;
    }

    @Override
    public boolean precondition(TestState model) {
        return !model.players.isEmpty()
                && index < model.players.size()
                && roundActive == ROUND_DRAFTING
                && !model.players.get(index).isBot()
                && !model.map.draftReady[model.map.getScripts().duelIndexFor(index)];
    }

    @Override
    public TestState run(TestState model) {
        Player player = model.players.get(index);
        model.map.getScripts().pukeScript(player, "QCDE_Duel_Use", true);
        model.simulation.runAtLeastTicks(5, model.simIdle);

        return model;
    }

    @Override
    public String toString() {
        return "DraftingSelectChampionAction{" +
                "index=" + index +
                '}';
    }
}

class WaitAction implements Action<TestState> {
    private final int tics;

    WaitAction(int tics) {
        this.tics = tics;
    }

    @Override
    public boolean precondition(TestState model) {
        return true;
    }

    @Override
    public TestState run(TestState model) {
        model.simulation.runAtLeastTicks(tics, model.simIdle);

        return model;
    }

    @Override
    public String toString() {
        return "WaitAction{" +
                "tics=" + tics +
                '}';
    }
}

class FragAction implements Action<TestState> {
    private final int who;

    FragAction(int player) {
        this.who = player;
    }

    @Override
    public boolean precondition(TestState model) {
        return model.players.size() == 2
                && model.players.get(1 - who).getHealth() > 0; // victim should be alive
    }

    @Override
    public TestState run(TestState model) {
        Player player = model.players.get(who);
        Player victim = model.players.get(1 - who);
        player.fragOther(victim);
        model.simulation.runAtLeastTicks(5, model.simIdle);

        return model;
    }

    @Override
    public String toString() {
        return "FragAction{" +
                "who=" + who +
                '}';
    }
}