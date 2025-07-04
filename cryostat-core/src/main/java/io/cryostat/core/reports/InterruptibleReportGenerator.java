/*
 * Copyright The Cryostat Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.cryostat.core.reports;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RunnableFuture;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.openjdk.jmc.common.item.IItemCollection;
import org.openjdk.jmc.common.unit.IQuantity;
import org.openjdk.jmc.common.util.IPreferenceValueProvider;
import org.openjdk.jmc.common.util.Pair;
import org.openjdk.jmc.flightrecorder.CouldNotLoadRecordingException;
import org.openjdk.jmc.flightrecorder.JfrLoaderToolkit;
import org.openjdk.jmc.flightrecorder.rules.DependsOn;
import org.openjdk.jmc.flightrecorder.rules.IRecordingSetting;
import org.openjdk.jmc.flightrecorder.rules.IResult;
import org.openjdk.jmc.flightrecorder.rules.IRule;
import org.openjdk.jmc.flightrecorder.rules.ResultBuilder;
import org.openjdk.jmc.flightrecorder.rules.ResultProvider;
import org.openjdk.jmc.flightrecorder.rules.ResultToolkit;
import org.openjdk.jmc.flightrecorder.rules.RuleRegistry;
import org.openjdk.jmc.flightrecorder.rules.Severity;
import org.openjdk.jmc.flightrecorder.rules.TypedResult;
import org.openjdk.jmc.flightrecorder.rules.util.RulesToolkit;

import org.apache.commons.io.input.CountingInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class InterruptibleReportGenerator {

    private final ExecutorService qThread = Executors.newCachedThreadPool();
    private final ExecutorService executor;
    private final Logger logger = LoggerFactory.getLogger(getClass());

    public InterruptibleReportGenerator(ExecutorService executor) {
        this.executor = executor;
    }

    public Future<Map<String, AnalysisResult>> generateEvalMapInterruptibly(
            InputStream recording, Predicate<IRule> predicate) {
        Objects.requireNonNull(recording);
        Objects.requireNonNull(predicate);
        return qThread.submit(
                () -> {
                    try {
                        Collection<IResult> results =
                                generateResultHelper(recording, predicate).left;
                        Map<String, AnalysisResult> evalMap = new HashMap<String, AnalysisResult>();
                        for (var eval : results) {
                            IQuantity scoreQuantity = eval.getResult(TypedResult.SCORE);
                            double score;
                            if (scoreQuantity != null) {
                                score = scoreQuantity.doubleValue();
                            } else {
                                score = eval.getSeverity().getLimit();
                            }
                            evalMap.put(eval.getRule().getId(), new AnalysisResult(score, eval));
                        }
                        return evalMap;
                    } catch (InterruptedException
                            | IOException
                            | ExecutionException
                            | CouldNotLoadRecordingException e) {
                        throw new CompletionException(e);
                    }
                });
    }

    private Pair<Collection<IResult>, Long> generateResultHelper(
            InputStream recording, Predicate<IRule> predicate)
            throws InterruptedException,
                    IOException,
                    ExecutionException,
                    CouldNotLoadRecordingException {
        Collection<IRule> rules = RuleRegistry.getRules();
        ResultProvider resultProvider = new ResultProvider();
        Map<IRule, Future<IResult>> resultFutures = new HashMap<>();
        Queue<RunnableFuture<IResult>> futureQueue = new ConcurrentLinkedQueue<>();
        // Map using the rule name as a key, and a Pair containing the rule (left) and it's
        // dependency (right)
        Map<String, Pair<IRule, IRule>> rulesWithDependencies = new HashMap<>();
        Map<IRule, IResult> computedResults = new HashMap<>();
        try (CountingInputStream countingRecordingStream = new CountingInputStream(recording)) {
            // TODO parsing the JFR file should also happen on the executor rather than the qThread,
            // so that this method can return to the qThread more quickly and free up the ability to
            // queue more work on the executor.
            IItemCollection items = JfrLoaderToolkit.loadEvents(countingRecordingStream);
            for (IRule rule : rules) {
                if (predicate.test(rule)
                        && RulesToolkit.matchesEventAvailabilityMap(
                                items, rule.getRequiredEvents())) {
                    if (hasDependency(rule)) {
                        IRule depRule =
                                rules.stream()
                                        .filter(r -> r.getId().equals(getRuleDependencyName(rule)))
                                        .findFirst()
                                        .orElse(null);
                        rulesWithDependencies.put(rule.getId(), new Pair<>(rule, depRule));
                    } else {
                        RunnableFuture<IResult> resultFuture =
                                rule.createEvaluation(
                                        items,
                                        IPreferenceValueProvider.DEFAULT_VALUES,
                                        resultProvider);
                        resultFutures.put(rule, resultFuture);
                        futureQueue.add(resultFuture);
                    }
                } else {
                    resultFutures.put(
                            rule,
                            CompletableFuture.completedFuture(
                                    ResultBuilder.createFor(
                                                    rule, IPreferenceValueProvider.DEFAULT_VALUES)
                                            .setSeverity(Severity.NA)
                                            .build()));
                }
            }
            // TODO this implementation forces rule dependencies to be evaluated on the qThread. The
            // executor is only used for rules that have no dependencies or which have all their
            // dependencies satisfied (by the qThread). Ideally we should perform all of the rule
            // evaluations on executor threads.
            for (Entry<String, Pair<IRule, IRule>> entry : rulesWithDependencies.entrySet()) {
                IRule rule = entry.getValue().left;
                IRule depRule = entry.getValue().right;
                Future<IResult> depResultFuture = resultFutures.get(depRule);
                if (depResultFuture == null) {
                    resultFutures.put(
                            rule,
                            CompletableFuture.completedFuture(
                                    ResultBuilder.createFor(
                                                    rule, IPreferenceValueProvider.DEFAULT_VALUES)
                                            .setSeverity(Severity.NA)
                                            .build()));
                } else {
                    IResult depResult = null;
                    if (!depResultFuture.isDone()) {
                        ((Runnable) depResultFuture).run();
                        try {
                            depResult = depResultFuture.get();
                            resultProvider.addResults(depResult);
                            computedResults.put(depRule, depResult);
                        } catch (InterruptedException | ExecutionException e) {
                            logger.warn("Error retrieving results for rule: " + depResult);
                        }
                    } else {
                        depResult = computedResults.get(depRule);
                    }
                    if (depResult != null && shouldEvaluate(rule, depResult)) {
                        RunnableFuture<IResult> resultFuture =
                                rule.createEvaluation(
                                        items,
                                        IPreferenceValueProvider.DEFAULT_VALUES,
                                        resultProvider);
                        resultFutures.put(rule, resultFuture);
                        futureQueue.add(resultFuture);
                    } else {
                        resultFutures.put(
                                rule,
                                CompletableFuture.completedFuture(
                                        ResultBuilder.createFor(
                                                        rule,
                                                        IPreferenceValueProvider.DEFAULT_VALUES)
                                                .setSeverity(Severity.NA)
                                                .build()));
                    }
                }
            }
            RunnableFuture<IResult> resultFuture;
            while ((resultFuture = futureQueue.poll()) != null) {
                executor.submit(resultFuture);
            }
            Collection<IResult> results = new HashSet<IResult>();
            for (Future<IResult> future : resultFutures.values()) {
                results.add(future.get());
            }
            return new Pair<Collection<IResult>, Long>(
                    results, countingRecordingStream.getByteCount());
        } catch (InterruptedException
                | IOException
                | ExecutionException
                | CouldNotLoadRecordingException e) {
            for (Future<?> f : resultFutures.values()) {
                if (!f.isDone()) {
                    f.cancel(true);
                }
            }
            logger.warn("Exception thrown", e);
            throw e;
        }
    }

    public static class AnalysisResult {
        private String name;
        private String topic;
        private double score;
        private Evaluation evaluation;

        AnalysisResult() {}

        AnalysisResult(String name, String topic, double score, Evaluation evaluation) {
            this.name = name;
            this.topic = topic;
            this.score = score;
            this.evaluation = evaluation;
        }

        AnalysisResult(double score, IResult result) {
            this(
                    result.getRule().getName(),
                    result.getRule().getTopic(),
                    score,
                    new Evaluation(result));
        }

        public double getScore() {
            return score;
        }

        public String getName() {
            return name;
        }

        public String getTopic() {
            return topic;
        }

        public Evaluation getEvaluation() {
            return evaluation;
        }

        public static class Evaluation {
            private String summary;
            private String explanation;
            private String solution;
            private List<Suggestion> suggestions;

            Evaluation() {}

            Evaluation(IResult result) {
                this.summary = ResultToolkit.populateMessage(result, result.getSummary(), false);
                this.explanation =
                        ResultToolkit.populateMessage(result, result.getExplanation(), false);
                this.solution = ResultToolkit.populateMessage(result, result.getSolution(), false);
                this.suggestions =
                        result.suggestRecordingSettings().stream()
                                .map(Suggestion::new)
                                .collect(Collectors.toList());
            }

            public String getSummary() {
                return summary;
            }

            public String getExplanation() {
                return explanation;
            }

            public String getSolution() {
                return solution;
            }

            public List<Suggestion> getSuggestions() {
                return Collections.unmodifiableList(suggestions);
            }

            public static class Suggestion {
                private String name;
                private String setting;
                private String value;

                Suggestion() {}

                Suggestion(IRecordingSetting setting) {
                    this.name = setting.getSettingName();
                    this.setting = setting.getSettingFor();
                    this.value = setting.getSettingValue();
                }

                public String getName() {
                    return name;
                }

                public String getSetting() {
                    return setting;
                }

                public String getValue() {
                    return value;
                }
            }
        }
    }

    private static String getRuleDependencyName(IRule rule) {
        DependsOn dependency = rule.getClass().getAnnotation(DependsOn.class);
        Class<? extends IRule> dependencyType = dependency.value();
        return dependencyType.getSimpleName();
    }

    private static boolean hasDependency(IRule rule) {
        DependsOn dependency = rule.getClass().getAnnotation(DependsOn.class);
        return dependency != null;
    }

    /** Brought over from org.openjdk.jmc.flightrecorder.rules.jdk.util.RulesToolkit */
    private static boolean shouldEvaluate(IRule rule, IResult depResult) {
        DependsOn dependency = rule.getClass().getAnnotation(DependsOn.class);
        if (dependency != null) {
            if (depResult.getSeverity().compareTo(dependency.severity()) < 0) {
                return false;
            }
        }
        return true;
    }
}
