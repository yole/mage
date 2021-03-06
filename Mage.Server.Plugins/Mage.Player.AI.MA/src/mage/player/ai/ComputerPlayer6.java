/*
 *  Copyright 2010 BetaSteward_at_googlemail.com. All rights reserved.
 *
 *  Redistribution and use in source and binary forms, with or without modification, are
 *  permitted provided that the following conditions are met:
 *
 *     1. Redistributions of source code must retain the above copyright notice, this list of
 *        conditions and the following disclaimer.
 *
 *     2. Redistributions in binary form must reproduce the above copyright notice, this list
 *        of conditions and the following disclaimer in the documentation and/or other materials
 *        provided with the distribution.
 *
 *  THIS SOFTWARE IS PROVIDED BY BetaSteward_at_googlemail.com ``AS IS'' AND ANY EXPRESS OR IMPLIED
 *  WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND
 *  FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL BetaSteward_at_googlemail.com OR
 *  CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 *  CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 *  SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON
 *  ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 *  NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF
 *  ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 *
 *  The views and conclusions contained in the software and documentation are those of the
 *  authors and should not be interpreted as representing official policies, either expressed
 *  or implied, of BetaSteward_at_googlemail.com.
 */
package mage.player.ai;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import mage.abilities.Ability;
import mage.abilities.ActivatedAbility;
import mage.abilities.SpellAbility;
import mage.abilities.common.PassAbility;
import mage.abilities.effects.Effect;
import mage.abilities.effects.SearchEffect;
import mage.abilities.keyword.DeathtouchAbility;
import mage.abilities.keyword.DoubleStrikeAbility;
import mage.abilities.keyword.ExaltedAbility;
import mage.abilities.keyword.FirstStrikeAbility;
import mage.abilities.keyword.FlyingAbility;
import mage.abilities.keyword.IndestructibleAbility;
import mage.abilities.keyword.ReachAbility;
import mage.cards.Card;
import mage.cards.Cards;
import mage.choices.Choice;
import mage.constants.AbilityType;
import mage.constants.Outcome;
import mage.constants.RangeOfInfluence;
import mage.game.Game;
import mage.game.combat.Combat;
import mage.game.events.GameEvent;
import mage.game.permanent.Permanent;
import mage.game.stack.StackAbility;
import mage.game.stack.StackObject;
import mage.player.ai.ma.optimizers.TreeOptimizer;
import mage.player.ai.ma.optimizers.impl.DiscardCardOptimizer;
import mage.player.ai.ma.optimizers.impl.EquipOptimizer;
import mage.player.ai.ma.optimizers.impl.LevelUpOptimizer;
import mage.player.ai.ma.optimizers.impl.OutcomeOptimizer;
import mage.player.ai.util.CombatInfo;
import mage.player.ai.util.CombatUtil;
import mage.players.Player;
import mage.target.Target;
import mage.target.TargetCard;
import mage.target.Targets;
import mage.util.RandomUtil;
import org.apache.log4j.Logger;

/**
 *
 * @author nantuko
 */
public class ComputerPlayer6 extends ComputerPlayer /*implements Player*/ {

    private static final Logger logger = Logger.getLogger(ComputerPlayer6.class);
    private static final ExecutorService pool = Executors.newFixedThreadPool(1);
    protected int maxDepth;
    protected int maxNodes;
    protected int maxThink;
    protected LinkedList<Ability> actions = new LinkedList<>();
    protected List<UUID> targets = new ArrayList<>();
    protected List<String> choices = new ArrayList<>();
    protected Combat combat;
    protected int currentScore;
    protected SimulationNode2 root;
    private static final String FILE_WITH_INSTRUCTIONS = "config/ai.please.cast.this.txt";
    private final List<String> suggested = new ArrayList<>();
    protected Set<String> actionCache;
    private static final List<TreeOptimizer> optimizers = new ArrayList<>();
    protected int lastLoggedTurn = 0;
    protected static final String BLANKS = "...............................................";

    static {
        optimizers.add(new LevelUpOptimizer());
        optimizers.add(new EquipOptimizer());
        optimizers.add(new DiscardCardOptimizer());
        optimizers.add(new OutcomeOptimizer());
    }

    public ComputerPlayer6(String name, RangeOfInfluence range, int skill) {
        super(name, range);
        if (skill < 4) {
            maxDepth = 4;
        } else {
            maxDepth = skill;
        }
        maxThink = skill * 3;
        maxNodes = Config2.maxNodes;
        getSuggestedActions();
        this.actionCache = new HashSet<>();
    }

    public ComputerPlayer6(final ComputerPlayer6 player) {
        super(player);
        this.maxDepth = player.maxDepth;
        this.currentScore = player.currentScore;
        if (player.combat != null) {
            this.combat = player.combat.copy();
        }
        this.actions.addAll(player.actions);
        this.targets.addAll(player.targets);
        this.choices.addAll(player.choices);
        this.actionCache = player.actionCache;
    }

    @Override
    public ComputerPlayer6 copy() {
        return new ComputerPlayer6(this);
    }

    protected void printOutState(Game game) {
        if (logger.isInfoEnabled()) {
            printOutState(game, playerId);
            for (UUID opponentId : game.getOpponents(playerId)) {
                printOutState(game, opponentId);
            }
        }
    }

    protected void printOutState(Game game, UUID playerId) {
        if (lastLoggedTurn != game.getTurnNum()) {
            lastLoggedTurn = game.getTurnNum();
            logger.info(new StringBuilder("------------------------ ").append("Turn: ").append(game.getTurnNum()).append("] --------------------------------------------------------------").toString());
        }

        Player player = game.getPlayer(playerId);
        logger.info(new StringBuilder("[").append(game.getPlayer(playerId).getName()).append("], life = ").append(player.getLife()).toString());
        StringBuilder sb = new StringBuilder("-> Hand: [");
        for (Card card : player.getHand().getCards(game)) {
            sb.append(card.getName()).append(';');
        }
        logger.info(sb.append(']').toString());
        sb.setLength(0);
        sb.append("-> Permanents: [");
        for (Permanent permanent : game.getBattlefield().getAllPermanents()) {
            if (permanent.getOwnerId().equals(player.getId())) {
                sb.append(permanent.getName());
                if (permanent.isTapped()) {
                    sb.append("(tapped)");
                }
                if (permanent.isAttacking()) {
                    sb.append("(attacking)");
                }
                sb.append(';');
            }
        }
        logger.info(sb.append(']').toString());
    }

    protected void act(Game game) {
        if (actions == null
                || actions.isEmpty()) {
            pass(game);
        } else {
            boolean usedStack = false;
            while (actions.peek() != null) {
                Ability ability = actions.poll();
                logger.info(new StringBuilder("===> Act [").append(game.getPlayer(playerId).getName()).append("] Action: ").append(ability.toString()).toString());
                if (!ability.getTargets().isEmpty()) {
                    for (Target target : ability.getTargets()) {
                        for (UUID id : target.getTargets()) {
                            target.updateTarget(id, game);
                            if (!target.isNotTarget()) {
                                game.addSimultaneousEvent(GameEvent.getEvent(GameEvent.EventType.TARGETED, id, ability.getSourceId(), ability.getControllerId()));
                            }
                        }
                    }
                    Player player = game.getPlayer(ability.getFirstTarget());
                    if (player != null) {
                        logger.info("targets = " + player.getName());
                    }
                }
                this.activateAbility((ActivatedAbility) ability, game);
                if (ability.isUsesStack()) {
                    usedStack = true;
                }
                if (!suggested.isEmpty() && !(ability instanceof PassAbility)) {
                    Iterator<String> it = suggested.iterator();
                    while (it.hasNext()) {
                        Card card = game.getCard(ability.getSourceId());
                        String action = it.next();
                        logger.info("Suggested action=" + action + ";card=" + card);
                        if (action.equals(card.getName())) {
                            logger.info("-> removed from suggested=" + action);
                            it.remove();
                        }
                    }
                }
            }
            if (usedStack) {
                pass(game);
            }
        }
    }

    protected int addActions(SimulationNode2 node, int depth, int alpha, int beta) {
        boolean stepFinished = false;
        int val;
        if (logger.isTraceEnabled()
                && node != null
                && node.getAbilities() != null
                && !node.getAbilities().toString().equals("[Pass]")) {
            logger.trace("Add Action [" + depth + "] " + node.getAbilities().toString() + "  a: " + alpha + " b: " + beta);
        }
        Game game = node.getGame();
        if (ALLOW_INTERRUPT
                && Thread.interrupted()) {
            Thread.currentThread().interrupt();
            logger.debug("interrupted");
            return GameStateEvaluator2.evaluate(playerId, game);
        }
        // Condition to stop deeper simulation
        if (depth <= 0
                || SimulationNode2.nodeCount > maxNodes
                || game.checkIfGameIsOver()) {
            val = GameStateEvaluator2.evaluate(playerId, game);
            if (logger.isTraceEnabled()) {
                StringBuilder sb = new StringBuilder("Add Actions -- reached end state  <").append(val).append('>');
                SimulationNode2 logNode = node;
                do {
                    sb.append(new StringBuilder(" <- [" + logNode.getDepth() + ']' + (logNode.getAbilities() != null ? logNode.getAbilities().toString() : "[empty]")));
                    logNode = logNode.getParent();
                } while ((logNode.getParent() != null));
                logger.trace(sb);
            }
        } else if (!node.getChildren().isEmpty()) {
            if (logger.isDebugEnabled()) {
                StringBuilder sb = new StringBuilder("Add Action [").append(depth)
                        .append("] -- something added children ")
                        .append(node.getAbilities() != null ? node.getAbilities().toString() : "null")
                        .append(" added children: ").append(node.getChildren().size()).append(" (");
                for (SimulationNode2 logNode : node.getChildren()) {
                    sb.append(logNode.getAbilities() != null ? logNode.getAbilities().toString() : "null").append(", ");
                }
                sb.append(')');
                logger.debug(sb);
            }
            val = minimaxAB(node, depth - 1, alpha, beta);
        } else {
            logger.trace("Add Action -- alpha: " + alpha + " beta: " + beta + " depth:" + depth + " step:" + game.getTurn().getStepType() + " for player:" + game.getPlayer(game.getPlayerList().get()).getName());
            if (allPassed(game)) {
                if (!game.getStack().isEmpty()) {
                    resolve(node, depth, game);
                } else {
                    stepFinished = true;
                }
            }

            if (game.checkIfGameIsOver()) {
                val = GameStateEvaluator2.evaluate(playerId, game);
            } else if (stepFinished) {
                logger.debug("Step finished");
                int testScore = GameStateEvaluator2.evaluate(playerId, game);
                if (game.getActivePlayerId().equals(playerId)) {
                    if (testScore < currentScore) {
                        // if score at end of step is worse than original score don't check further
                        //logger.debug("Add Action -- abandoning check, no immediate benefit");
                        val = testScore;
                    } else {
                        val = GameStateEvaluator2.evaluate(playerId, game);
                    }
                } else {
                    val = GameStateEvaluator2.evaluate(playerId, game);
                }
            } else if (!node.getChildren().isEmpty()) {
                if (logger.isDebugEnabled()) {
                    StringBuilder sb = new StringBuilder("Add Action [").append(depth)
                            .append("] -- trigger ")
                            .append(node.getAbilities() != null ? node.getAbilities().toString() : "null")
                            .append(" added children: ").append(node.getChildren().size()).append(" (");
                    for (SimulationNode2 logNode : node.getChildren()) {
                        sb.append(logNode.getAbilities() != null ? logNode.getAbilities().toString() : "null").append(", ");
                    }
                    sb.append(')');
                    logger.debug(sb);
                }
                val = minimaxAB(node, depth - 1, alpha, beta);
            } else {
                val = simulatePriority(node, game, depth, alpha, beta);
            }
        }
        node.setScore(val);
        logger.trace("returning -- score: " + val + " depth:" + depth + " step:" + game.getTurn().getStepType() + " for player:" + game.getPlayer(node.getPlayerId()).getName());
        return val;

    }

    protected boolean getNextAction(Game game) {
        if (root != null
                && !root.children.isEmpty()) {
            SimulationNode2 test = root;
            root = root.children.get(0);
            while (!root.children.isEmpty()
                    && !root.playerId.equals(playerId)) {
                test = root;
                root = root.children.get(0);
            }
            logger.trace("Sim getNextAction -- game value:" + game.getState().getValue(true) + " test value:" + test.gameValue);
            if (!suggested.isEmpty()) {
                return false;
            }
            if (root.playerId.equals(playerId)
                    && root.abilities != null
                    && game.getState().getValue(true).hashCode() == test.gameValue) {
                logger.info("simulating -- continuing previous action chain");
                actions = new LinkedList<>(root.abilities);
                combat = root.combat;
                return true;
            } else {
                return false;
            }
        }
        return false;
    }

    protected int minimaxAB(SimulationNode2 node, int depth, int alpha, int beta) {
        logger.trace("Sim minimaxAB [" + depth + "] -- a: " + alpha + " b: " + beta + " <" + (node != null ? node.getScore() : "null") + '>');
        UUID currentPlayerId = node.getGame().getPlayerList().get();
        SimulationNode2 bestChild = null;
        for (SimulationNode2 child : node.getChildren()) {
            Combat _combat = child.getCombat();
            if (alpha >= beta) {
                break;
            }
            if (SimulationNode2.nodeCount > maxNodes) {
                break;
            }
            int val = addActions(child, depth - 1, alpha, beta);
            if (!currentPlayerId.equals(playerId)) {
                if (val < beta) {
                    beta = val;
                    bestChild = child;
                    if (node.getCombat() == null) {
                        node.setCombat(_combat);
                        bestChild.setCombat(_combat);
                    }
                }
                // no need to check other actions
                if (val == GameStateEvaluator2.LOSE_GAME_SCORE) {
                    logger.debug("lose - break");
                    break;
                }
            } else {
                if (val > alpha) {
                    alpha = val;
                    bestChild = child;
                    if (node.getCombat() == null) {
                        node.setCombat(_combat);
                        bestChild.setCombat(_combat);
                    }
                }
                // no need to check other actions
                if (val == GameStateEvaluator2.WIN_GAME_SCORE) {
                    logger.debug("win - break");
                    break;
                }
            }
        }
        node.children.clear();
        if (bestChild != null) {
            node.children.add(bestChild);
        }
        if (!currentPlayerId.equals(playerId)) {
            return beta;
        } else {
            return alpha;
        }
    }

    protected SearchEffect getSearchEffect(StackAbility ability) {
        for (Effect effect : ability.getEffects()) {
            if (effect instanceof SearchEffect) {
                return (SearchEffect) effect;
            }
        }
        return null;
    }

    protected void resolve(SimulationNode2 node, int depth, Game game) {
        StackObject stackObject = game.getStack().getFirst();
        if (stackObject instanceof StackAbility) {
            SearchEffect effect = getSearchEffect((StackAbility) stackObject);
            if (effect != null
                    && stackObject.getControllerId().equals(playerId)) {
                Target target = effect.getTarget();
                if (!target.doneChosing()) {
                    for (UUID targetId : target.possibleTargets(stackObject.getSourceId(), stackObject.getControllerId(), game)) {
                        Game sim = game.copy();
                        StackAbility newAbility = (StackAbility) stackObject.copy();
                        SearchEffect newEffect = getSearchEffect(newAbility);
                        newEffect.getTarget().addTarget(targetId, newAbility, sim);
                        sim.getStack().push(newAbility);
                        SimulationNode2 newNode = new SimulationNode2(node, sim, depth, stackObject.getControllerId());
                        node.children.add(newNode);
                        newNode.getTargets().add(targetId);
                        logger.trace("Sim search -- node#: " + SimulationNode2.getCount() + " for player: " + sim.getPlayer(stackObject.getControllerId()).getName());
                    }
                    return;
                }
            }
        }
        stackObject.resolve(game);
        if (stackObject instanceof StackAbility) {
            game.getStack().remove(stackObject);
        }
        game.applyEffects();
        game.getPlayers().resetPassed();
        game.getPlayerList().setCurrent(game.getActivePlayerId());
    }

    /**
     * Base call for simulation of AI actions
     *
     * @return
     */
    protected Integer addActionsTimed() {
        FutureTask<Integer> task = new FutureTask<>(new Callable<Integer>() {
            @Override
            public Integer call() throws Exception {
                return addActions(root, maxDepth, Integer.MIN_VALUE, Integer.MAX_VALUE);
            }
        });
        pool.execute(task);
        try {
            int maxSeconds = maxThink;
            if (!ALLOW_INTERRUPT) {
                maxSeconds = 3600;
            }
            logger.debug("maxThink: " + maxSeconds + " seconds ");
            if (task.get(maxSeconds, TimeUnit.SECONDS) != null) {
                return task.get(maxSeconds, TimeUnit.SECONDS);
            }
        } catch (TimeoutException e) {
            logger.info("simulating - timed out");
            task.cancel(true);
        } catch (ExecutionException e) {
            e.printStackTrace();
            task.cancel(true);
        } catch (InterruptedException e) {
            e.printStackTrace();
            task.cancel(true);
        } catch (Exception e) {
            e.printStackTrace();
            task.cancel(true);
        }
        //TODO: timeout handling
        return 0;
    }

    protected int simulatePriority(SimulationNode2 node, Game game, int depth, int alpha, int beta) {
        if (ALLOW_INTERRUPT
                && Thread.interrupted()) {
            Thread.currentThread().interrupt();
            logger.info("interrupted");
            return GameStateEvaluator2.evaluate(playerId, game);
        }
        node.setGameValue(game.getState().getValue(true).hashCode());
        SimulatedPlayer2 currentPlayer = (SimulatedPlayer2) game.getPlayer(game.getPlayerList().get());
        SimulationNode2 bestNode = null;
        List<Ability> allActions = currentPlayer.simulatePriority(game);
        optimize(game, allActions);
        if (logger.isInfoEnabled()
                && !allActions.isEmpty()
                && depth == maxDepth) {
            logger.info("ADDED ACTIONS (" + allActions.size() + ") " + ' ' + allActions);
        }
        int counter = 0;
        int bestValSubNodes = Integer.MIN_VALUE;
        for (Ability action : allActions) {
            counter++;
            if (ALLOW_INTERRUPT
                    && Thread.interrupted()) {
                Thread.currentThread().interrupt();
                logger.info("Sim Prio [" + depth + "] -- interrupted");
                break;
            }
            Game sim = game.copy();
            sim.setSimulation(true);
            if (sim.getPlayer(currentPlayer.getId()).activateAbility((ActivatedAbility) action.copy(), sim)) {
                sim.applyEffects();
                if (checkForRepeatedAction(sim, node, action, currentPlayer.getId())) {
                    logger.debug("Sim Prio [" + depth + "] -- repeated action: " + action.toString());
                    continue;
                }
                if (!sim.checkIfGameIsOver()
                        && action.isUsesStack()) {
                    // only pass if the last action uses the stack
                    UUID nextPlayerId = sim.getPlayerList().get();
                    do {
                        sim.getPlayer(nextPlayerId).pass(game);
                        nextPlayerId = sim.getPlayerList().getNext();
                    } while (nextPlayerId != this.getId());
                }
                SimulationNode2 newNode = new SimulationNode2(node, sim, action, depth, currentPlayer.getId());
                sim.checkStateAndTriggered();
                int val;
                if (action instanceof PassAbility) {
                    // Stop to simulate deeper if PassAbility
                    val = GameStateEvaluator2.evaluate(this.getId(), sim);
                } else {
                    val = addActions(newNode, depth - 1, alpha, beta);
                }
                logger.debug("Sim Prio " + BLANKS.substring(0, 2 + (maxDepth - depth) * 3) + '[' + depth + "]#" + counter + " <" + val + "> - (" + action.toString() + ") ");
                if (logger.isInfoEnabled()
                        && depth >= maxDepth) {
                    StringBuilder sb = new StringBuilder("Sim Prio [").append(depth).append("] #").append(counter)
                            .append(" <").append(val).append("> (").append(action)
                            .append(action.isModal() ? " Mode = " + action.getModes().getMode().toString() : "")
                            .append(listTargets(game, action.getTargets())).append(')')
                            .append(logger.isTraceEnabled() ? " #" + newNode.hashCode() : "");
                    SimulationNode2 logNode = newNode;
                    while (logNode.getChildren() != null
                            && !logNode.getChildren().isEmpty()) {
                        logNode = logNode.getChildren().get(0);
                        if (logNode.getAbilities() != null
                                && !logNode.getAbilities().isEmpty()) {
                            sb.append(" -> [").append(logNode.getDepth()).append(']').append(logNode.getAbilities().toString()).append('<').append(logNode.getScore()).append('>');
                        }
                    }
                    logger.info(sb);
                }

                if (currentPlayer.getId().equals(playerId)) {
                    if (val > bestValSubNodes) {
                        bestValSubNodes = val;
                    }
                    if (depth == maxDepth
                            && action instanceof PassAbility) {
                        val = val - PASSIVITY_PENALTY; // passivity penalty
                    }
                    if (val > alpha
                            || (depth == maxDepth
                            && val == alpha
                            && RandomUtil.nextBoolean())) { // Adding random for equal value to get change sometimes
                        alpha = val;
                        bestNode = newNode;
                        bestNode.setScore(val);
                        if (!newNode.getChildren().isEmpty()) {
                            bestNode.setCombat(newNode.getChildren().get(0).getCombat());
                        }
                        if (depth == maxDepth) {
                            logger.info("Sim Prio [" + depth + "] -- Saved best node yet <" + bestNode.getScore() + "> " + bestNode.getAbilities().toString());
                            node.children.clear();
                            node.children.add(bestNode);
                            node.setScore(bestNode.getScore());
                        }
                    }

                    // no need to check other actions
                    if (val == GameStateEvaluator2.WIN_GAME_SCORE) {
                        logger.debug("Sim Prio -- win - break");
                        break;
                    }
                } else {
                    if (val < beta) {
                        beta = val;
                        bestNode = newNode;
                        bestNode.setScore(val);
                        if (!newNode.getChildren().isEmpty()) {
                            bestNode.setCombat(newNode.getChildren().get(0).getCombat());
                        }
                    }

                    // no need to check other actions
                    if (val == GameStateEvaluator2.LOSE_GAME_SCORE) {
                        logger.debug("Sim Prio -- lose - break");
                        break;
                    }
                }
                if (alpha >= beta) {
                    break;
                }
                if (SimulationNode2.nodeCount > maxNodes) {
                    logger.debug("Sim Prio -- reached end-state");
                    break;
                }
            }
        } // end of for (allActions)

        if (depth == maxDepth) {
            logger.info(new StringBuilder("Sim Prio [").append(depth).append("] -- End for Max Depth  -- Nodes calculated: ").append(SimulationNode2.nodeCount));
        }
        if (bestNode != null) {
            node.children.clear();
            node.children.add(bestNode);
            node.setScore(bestNode.getScore());
            if (logger.isTraceEnabled()
                    && !bestNode.getAbilities().toString().equals("[Pass]")) {
                logger.trace(new StringBuilder("Sim Prio [").append(depth).append("] -- Set after (depth=").append(depth).append(")  <").append(bestNode.getScore()).append("> ").append(bestNode.getAbilities().toString()).toString());
            }
        }

        if (currentPlayer.getId().equals(playerId)) {
            return bestValSubNodes;
        } else {
            return beta;
        }
    }

    /**
     * Various AI optimizations for actions.
     *
     * @param game
     * @param allActions
     */
    protected void optimize(Game game, List<Ability> allActions) {
        for (TreeOptimizer optimizer : optimizers) {
            optimizer.optimize(game, allActions);
        }
        Collections.sort(allActions, new Comparator<Ability>() {
            @Override
            public int compare(Ability ability, Ability ability1) {
                String rule = ability.toString();
                String rule1 = ability1.toString();
                if (rule.equals("Pass")) {
                    return 1;
                }
                if (rule1.equals("Pass")) {
                    return -1;
                }
                if (rule.startsWith("Play")) {
                    return -1;
                }
                if (rule1.startsWith("Play")) {
                    return 1;
                }
                if (rule.startsWith("Cast")) {
                    return -1;
                }
                if (rule1.startsWith("Cast")) {
                    return 1;
                }
                return ability.getRule().compareTo(ability1.getRule());
            }
        });
    }

    protected boolean allPassed(Game game) {
        for (Player player : game.getPlayers().values()) {
            if (!player.isPassed()
                    && !player.hasLost()
                    && !player.hasLeft()) {
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean choose(Outcome outcome, Choice choice, Game game) {
        if (choices.isEmpty()) {
            return super.choose(outcome, choice, game);
        }
        if (!choice.isChosen()) {
            if (!choice.setChoiceByAnswers(choices, true)) {
                choice.setRandomChoice();
            }
        }
        return true;
    }

    @Override
    public boolean chooseTarget(Outcome outcome, Cards cards, TargetCard target, Ability source, Game game) {
        if (targets.isEmpty()) {
            return super.chooseTarget(outcome, cards, target, source, game);
        }
        if (!target.doneChosing()) {
            for (UUID targetId : targets) {
                target.addTarget(targetId, source, game);
                if (target.doneChosing()) {
                    targets.clear();
                    return true;
                }
            }
            return false;
        }
        return true;
    }

    @Override
    public boolean choose(Outcome outcome, Cards cards, TargetCard target, Game game) {
        if (targets.isEmpty()) {
            return super.choose(outcome, cards, target, game);
        }
        if (!target.doneChosing()) {
            for (UUID targetId : targets) {
                target.add(targetId, game);
                if (target.doneChosing()) {
                    targets.clear();
                    return true;
                }
            }
            return false;
        }
        return true;
    }

    private void declareBlockers(Game game, UUID activePlayerId) {
        game.fireEvent(new GameEvent(GameEvent.EventType.DECLARE_BLOCKERS_STEP_PRE, null, null, activePlayerId));
        if (!game.replaceEvent(GameEvent.getEvent(GameEvent.EventType.DECLARING_BLOCKERS, activePlayerId, activePlayerId))) {
            List<Permanent> attackers = getAttackers(game);
            if (attackers == null) {
                return;
            }

            List<Permanent> possibleBlockers = super.getAvailableBlockers(game);
            possibleBlockers = filterOutNonblocking(game, attackers, possibleBlockers);
            if (possibleBlockers.isEmpty()) {
                return;
            }

            attackers = filterOutUnblockable(game, attackers, possibleBlockers);
            if (attackers.isEmpty()) {
                return;
            }

            CombatUtil.sortByPower(attackers, false);

            CombatInfo combatInfo = CombatUtil.blockWithGoodTrade2(game, attackers, possibleBlockers);
            Player player = game.getPlayer(playerId);

            boolean blocked = false;
            for (Map.Entry<Permanent, List<Permanent>> entry : combatInfo.getCombat().entrySet()) {
                UUID attackerId = entry.getKey().getId();
                List<Permanent> blockers = entry.getValue();
                if (blockers != null) {
                    for (Permanent blocker : blockers) {
                        player.declareBlocker(player.getId(), blocker.getId(), attackerId, game);
                        blocked = true;
                    }
                }
            }
            if (blocked) {
                game.getPlayers().resetPassed();
            }
        }
    }

    private List<Permanent> filterOutNonblocking(Game game, List<Permanent> attackers, List<Permanent> blockers) {
        List<Permanent> blockersLeft = new ArrayList<>();
        for (Permanent blocker : blockers) {
            for (Permanent attacker : attackers) {
                if (blocker.canBlock(attacker.getId(), game)) {
                    blockersLeft.add(blocker);
                    break;
                }
            }
        }
        return blockersLeft;
    }

    private List<Permanent> filterOutUnblockable(Game game, List<Permanent> attackers, List<Permanent> blockers) {
        List<Permanent> attackersLeft = new ArrayList<>();
        for (Permanent attacker : attackers) {
            if (CombatUtil.canBeBlocked(game, attacker, blockers)) {
                attackersLeft.add(attacker);
            }
        }
        return attackersLeft;
    }

    private List<Permanent> getAttackers(Game game) {
        List<UUID> attackersUUID = game.getCombat().getAttackers();
        if (attackersUUID.isEmpty()) {
            return null;
        }

        List<Permanent> attackers = new ArrayList<>();
        for (UUID attackerId : attackersUUID) {
            Permanent permanent = game.getPermanent(attackerId);
            attackers.add(permanent);
        }
        return attackers;
    }

    /**
     * Choose attackers based on static information. That means that AI won't
     * look to the future as it was before, but just choose attackers based on
     * current state of the game. This is worse, but at least it is easier to
     * implement and won't lead to the case when AI doesn't do anything -
     * neither attack nor block.
     *
     * @param game
     * @param activePlayerId
     */
    private void declareAttackers(Game game, UUID activePlayerId) {
        game.fireEvent(new GameEvent(GameEvent.EventType.DECLARE_ATTACKERS_STEP_PRE, null, null, activePlayerId));
        if (!game.replaceEvent(GameEvent.getEvent(GameEvent.EventType.DECLARING_ATTACKERS, activePlayerId, activePlayerId))) {

            Player attackingPlayer = game.getPlayer(activePlayerId);
            // TODO: this works only in two player game, also no attack of Planeswalker
            UUID defenderId = game.getOpponents(playerId).iterator().next();
            Player defender = game.getPlayer(defenderId);

            List<Permanent> attackersList = super.getAvailableAttackers(defenderId, game);
            if (attackersList.isEmpty()) {
                return;
            }

            List<Permanent> possibleBlockers = defender.getAvailableBlockers(game);

            List<Permanent> killers = CombatUtil.canKillOpponent(game, attackersList, possibleBlockers, defender);
            if (!killers.isEmpty()) {
                for (Permanent attacker : killers) {
                    attackingPlayer.declareAttacker(attacker.getId(), defenderId, game, false);
                }
                return;
            }

            // The AI will now attack more sanely.  Simple, but good enough for now.
            // The sim minmax does not work at the moment.
            boolean safeToAttack;
            CombatEvaluator eval = new CombatEvaluator();

            for (Permanent attacker : attackersList) {
                safeToAttack = true;
                int attackerValue = eval.evaluate(attacker, game);
                for (Permanent blocker : possibleBlockers) {
                    int blockerValue = eval.evaluate(blocker, game);
                    if (attacker.getPower().getValue() <= blocker.getToughness().getValue()
                            && attacker.getToughness().getValue() <= blocker.getPower().getValue()) {
                        safeToAttack = false;
                    }
                    if (attacker.getToughness().getValue() == blocker.getPower().getValue()
                            && attacker.getPower().getValue() == blocker.getToughness().getValue()) {
                        if (attackerValue > blockerValue
                                || blocker.getAbilities().containsKey(FirstStrikeAbility.getInstance().getId())
                                || blocker.getAbilities().containsKey(DoubleStrikeAbility.getInstance().getId())
                                || blocker.getAbilities().contains(new ExaltedAbility())
                                || blocker.getAbilities().containsKey(DeathtouchAbility.getInstance().getId())
                                || blocker.getAbilities().containsKey(IndestructibleAbility.getInstance().getId())
                                || !attacker.getAbilities().containsKey(FirstStrikeAbility.getInstance().getId())
                                || !attacker.getAbilities().containsKey(DoubleStrikeAbility.getInstance().getId())
                                || !attacker.getAbilities().contains(new ExaltedAbility())) {
                            safeToAttack = false;
                        }
                    }
                    if (attacker.getAbilities().containsKey(DeathtouchAbility.getInstance().getId())
                            || attacker.getAbilities().containsKey(IndestructibleAbility.getInstance().getId())) {
                        safeToAttack = true;
                    }
                    if (attacker.getAbilities().containsKey(FlyingAbility.getInstance().getId())
                            && !blocker.getAbilities().containsKey(FlyingAbility.getInstance().getId())
                            && !blocker.getAbilities().containsKey(ReachAbility.getInstance().getId())) {
                        safeToAttack = true;
                    }
                }
                if (attacker.getPower().getValue() == 0) {
                    safeToAttack = false;
                }
                if (safeToAttack) {
                    // undo has to be possible e.g. if not able to pay a attack fee (e.g. Ghostly Prison)
                    attackingPlayer.declareAttacker(attacker.getId(), defenderId, game, true);
                }
            }
        }
    }

    @Override
    public void selectAttackers(Game game, UUID attackingPlayerId) {
        logger.debug("selectAttackers");
        declareAttackers(game, playerId);
    }

    @Override
    public void selectBlockers(Game game, UUID defendingPlayerId) {
        logger.debug("selectBlockers");
        declareBlockers(game, playerId);
    }

    /**
     * Copies game and replaces all players in copy with simulated players
     *
     * @param game
     * @return a new game object with simulated players
     */
    protected Game createSimulation(Game game) {
        Game sim = game.copy();
        sim.setSimulation(true);
        for (Player copyPlayer : sim.getState().getPlayers().values()) {
            Player origPlayer = game.getState().getPlayers().get(copyPlayer.getId()).copy();
            if (!suggested.isEmpty()) {
                logger.debug(origPlayer.getName() + " suggested: " + suggested);
            }
            SimulatedPlayer2 newPlayer = new SimulatedPlayer2(copyPlayer.getId(), copyPlayer.getId().equals(playerId), suggested);
            newPlayer.restore(origPlayer);
            sim.getState().getPlayers().put(copyPlayer.getId(), newPlayer);
        }
        return sim;
    }

    private boolean checkForRepeatedAction(Game sim, SimulationNode2 node, Ability action, UUID playerId) {
        // pass or casting two times a spell multiple times on hand is ok
        if (action instanceof PassAbility || action instanceof SpellAbility || action.getAbilityType() == AbilityType.MANA) {
            return false;
        }
        int newVal = GameStateEvaluator2.evaluate(playerId, sim);
        SimulationNode2 test = node.getParent();
        while (test != null) {
            if (test.getPlayerId().equals(playerId)) {
                if (test.getAbilities() != null && test.getAbilities().size() == 1) {
                    if (action.toString().equals(test.getAbilities().get(0).toString())) {
                        if (test.getParent() != null) {
                            Game prevGame = node.getGame();
                            if (prevGame != null) {
                                int oldVal = GameStateEvaluator2.evaluate(playerId, prevGame);
                                if (oldVal >= newVal) {
                                    return true;
                                }
                            }
                        }
                    }
                }
            }
            test = test.getParent();
        }
        return false;
    }

    protected final void getSuggestedActions() {
        try {
            File file = new File(FILE_WITH_INSTRUCTIONS);
            if (file.exists()) {
                Scanner scanner = new Scanner(file);
                while (scanner.hasNextLine()) {
                    String line = scanner.nextLine();
                    if (line.startsWith("cast:")
                            || line.startsWith("play:")) {
                        suggested.add(line.substring(5, line.length()));
                    }
                }
                System.out.println("suggested::");
                for (int i = 0; i < suggested.size(); i++) {
                    System.out.println("    " + suggested.get(i));
                }
            }
        } catch (Exception e) {
            // swallow
            e.printStackTrace();
        }
    }

    @Override
    public void addAction(String action) {
        if (action != null
                && (action.startsWith("cast:")
                || action.startsWith("play:"))) {
            suggested.add(action.substring(5, action.length()));
        }
    }

    @Override
    public int getActionCount() {
        return suggested.size();
    }

    protected String listTargets(Game game, Targets targets) {
        StringBuilder sb = new StringBuilder();
        if (targets != null) {
            for (Target target : targets) {
                sb.append('[').append(target.getTargetedName(game)).append(']');
            }
            if (sb.length() > 0) {
                sb.insert(0, " targeting ");
            }
        }
        return sb.toString();
    }

    @Override
    public void cleanUpOnMatchEnd() {
        root = null;
        super.cleanUpOnMatchEnd();
    }

}
