package io.appform.statesman.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.base.Preconditions;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import io.appform.hope.core.Evaluatable;
import io.appform.hope.core.exceptions.errorstrategy.DefaultErrorHandlingStrategy;
import io.appform.hope.lang.HopeLangEngine;
import io.appform.statesman.engine.observer.ObservableEventBus;
import io.appform.statesman.engine.observer.events.StateTransitionEvent;
import io.appform.statesman.model.DataObject;
import io.appform.statesman.model.DataUpdate;
import lombok.extern.slf4j.Slf4j;
import lombok.val;
import lombok.var;

import javax.inject.Inject;
import javax.inject.Provider;
import javax.inject.Singleton;

/**
 *
 */
@Singleton
@Slf4j
public class StateTransitionEngine {
    private final Provider<WorkflowProvider> workflowProvider;
    private final Provider<TransitionStore> transitionStore;
    private final ObjectMapper mapper;
    private final ObservableEventBus eventBus;
    private final HopeLangEngine hopeLangEngine;

    private final Cache<String, Evaluatable> evalCache = CacheBuilder.newBuilder()
            .maximumSize(100_000)
            .build();
    @Inject
    public StateTransitionEngine(
            Provider<WorkflowProvider> workflowProvider,
            Provider<TransitionStore> transitionStore,
            ObjectMapper mapper,
            ObservableEventBus eventBus) {
        this.workflowProvider = workflowProvider;
        this.transitionStore = transitionStore;
        this.mapper = mapper;
        this.eventBus = eventBus;
        this.hopeLangEngine = HopeLangEngine.builder()
                .errorHandlingStrategy(new DefaultErrorHandlingStrategy())
                .build();
    }

    void handle(DataUpdate dataUpdate) {
        val workflowId = dataUpdate.getWorkflowId();
        val workflow = workflowProvider.get()
                .getWorkflow(workflowId)
                .orElse(null);
        Preconditions.checkNotNull(workflow);
        final DataObject dataObject = workflow.getDataObject();
        val currentState = dataObject.getCurrentState();
        if(currentState.isTerminal()) {
            log.info("Workflow {} is already complete.", workflow.getId());
            return;
        }
        val template = workflowProvider.get()
                .getTemplate(workflow.getTemplateId())
                .orElse(null);
        Preconditions.checkNotNull(template);
        val transitions = transitionStore.get()
                .getTransitionFor(template.getId(), currentState.getName())
                .orElse(null);
        Preconditions.checkNotNull(transitions);
        val evalNode = mapper.createObjectNode();
        evalNode.putObject("data").setAll((ObjectNode) dataObject.getData());
        evalNode.putObject("update").setAll((ObjectNode)dataUpdate.getData());
        val selectedTransition = transitions.stream()
                .filter(stateTransition -> {
                    val transitionRule = stateTransition.getRule();
                    var rule = evalCache.getIfPresent(transitionRule.getId());
                    if(null == rule) {
                        rule = hopeLangEngine.parse(transitionRule.getRule());
                        evalCache.put(transitionRule.getId(), rule);
                    }
                    return hopeLangEngine.evaluate(rule, evalNode);
                })
                .findFirst()
                .orElse(null);
        Preconditions.checkNotNull(selectedTransition);
        dataObject.setData(DataActionExecutor.apply(dataObject, dataUpdate));
        dataObject.setCurrentState(selectedTransition.getToState());

        eventBus.publish(new StateTransitionEvent(template, workflow, dataUpdate, currentState, selectedTransition));
    }
}
