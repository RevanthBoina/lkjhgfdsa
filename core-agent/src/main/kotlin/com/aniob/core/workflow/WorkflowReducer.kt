package com.aniob.core.workflow

/**
 * Pure state transition reducer for workflows.
 * No I/O or side effects.
 */
object WorkflowReducer {

    sealed class TransitionEvent {
        data class DiscoveredChoice(val candidates: List<Destination>, val prompt: String) : TransitionEvent()
        data class MissingAccess(val requiredGrant: String, val reason: String) : TransitionEvent()
        data class RequestApproval(
            val identity: OperationIdentity,
            val action: WorkflowAction,
            val destination: Destination,
            val summary: String
        ) : TransitionEvent()
        data class MarkReady(val identity: OperationIdentity, val action: WorkflowAction) : TransitionEvent()
        data class StartDispatch(val identity: OperationIdentity, val action: WorkflowAction, val payloadHash: String?) : TransitionEvent()
        data class LaunchAccepted(val identity: OperationIdentity, val receiptId: String) : TransitionEvent()
        data class RequireUserHandoff(val identity: OperationIdentity, val destination: Destination, val brief: String?) : TransitionEvent()
        data class ReceiveResult(val identity: OperationIdentity, val result: WorkflowResult) : TransitionEvent()
        data class Complete(val identity: OperationIdentity, val result: WorkflowResult) : TransitionEvent()
        data class Fail(val identity: OperationIdentity?, val reason: String) : TransitionEvent()
        data class Cancel(val identity: OperationIdentity?, val reason: String) : TransitionEvent()
        data class Interrupt(val identity: OperationIdentity?, val reason: String) : TransitionEvent()
        data class MarkEffectUnknown(val identity: OperationIdentity?, val reason: String) : TransitionEvent()
    }

    /**
     * Reduces the current state with an event into a new valid state.
     * Returns null or unchanged/Failed if the transition is invalid or identities mismatch.
     */
    fun reduce(currentState: WorkflowState, event: TransitionEvent): WorkflowState {
        return when (event) {
            is TransitionEvent.DiscoveredChoice -> {
                when (currentState) {
                    is WorkflowState.Draft -> WorkflowState.NeedsChoice(event.candidates, event.prompt)
                    else -> currentState
                }
            }

            is TransitionEvent.MissingAccess -> {
                when (currentState) {
                    is WorkflowState.Draft,
                    is WorkflowState.NeedsChoice,
                    is WorkflowState.Ready -> WorkflowState.NeedsAccess(event.requiredGrant, event.reason)
                    else -> currentState
                }
            }

            is TransitionEvent.RequestApproval -> {
                when (currentState) {
                    is WorkflowState.Draft,
                    is WorkflowState.NeedsChoice,
                    is WorkflowState.Ready -> {
                        WorkflowState.NeedsApproval(
                            identity = event.identity,
                            action = event.action,
                            destination = event.destination,
                            summary = event.summary
                        )
                    }
                    else -> currentState
                }
            }

            is TransitionEvent.MarkReady -> {
                when (currentState) {
                    is WorkflowState.Draft,
                    is WorkflowState.NeedsChoice,
                    is WorkflowState.NeedsApproval,
                    is WorkflowState.NeedsAccess -> {
                        WorkflowState.Ready(event.identity, event.action)
                    }
                    else -> currentState
                }
            }

            is TransitionEvent.StartDispatch -> {
                when (currentState) {
                    is WorkflowState.Ready -> {
                        if (currentState.identity == event.identity) {
                            WorkflowState.Dispatching(
                                identity = event.identity,
                                action = event.action,
                                payloadHash = event.payloadHash
                            )
                        } else {
                            currentState
                        }
                    }
                    else -> currentState
                }
            }

            is TransitionEvent.RequireUserHandoff -> {
                when (currentState) {
                    is WorkflowState.Dispatching -> {
                        if (isMatchingIdentity(currentState.identity, event.identity)) {
                            WorkflowState.WaitingForUser(
                                identity = event.identity,
                                destination = event.destination,
                                brief = event.brief
                            )
                        } else {
                            currentState
                        }
                    }
                    else -> currentState
                }
            }

            is TransitionEvent.LaunchAccepted -> {
                when (currentState) {
                    is WorkflowState.Dispatching -> {
                        if (isMatchingIdentity(currentState.identity, event.identity)) {
                            WorkflowState.ResultReady(
                                identity = event.identity,
                                result = WorkflowResult.LaunchAccepted(event.receiptId)
                            )
                        } else {
                            currentState
                        }
                    }
                    else -> currentState
                }
            }

            is TransitionEvent.ReceiveResult -> {
                when (currentState) {
                    is WorkflowState.Dispatching -> {
                        if (isMatchingIdentity(currentState.identity, event.identity)) {
                            WorkflowState.ResultReady(event.identity, event.result)
                        } else {
                            currentState
                        }
                    }
                    is WorkflowState.WaitingForUser -> {
                        if (isMatchingIdentity(currentState.identity, event.identity)) {
                            WorkflowState.ResultReady(event.identity, event.result)
                        } else {
                            currentState
                        }
                    }
                    else -> currentState
                }
            }

            is TransitionEvent.Complete -> {
                when (currentState) {
                    is WorkflowState.ResultReady -> {
                        if (isMatchingIdentity(currentState.identity, event.identity)) {
                            WorkflowState.Completed(event.identity, event.result)
                        } else {
                            currentState
                        }
                    }
                    is WorkflowState.WaitingForUser -> {
                        if (isMatchingIdentity(currentState.identity, event.identity)) {
                            WorkflowState.Completed(event.identity, event.result)
                        } else {
                            currentState
                        }
                    }
                    else -> currentState
                }
            }

            is TransitionEvent.Fail -> {
                WorkflowState.Failed(event.identity, event.reason)
            }

            is TransitionEvent.Cancel -> {
                WorkflowState.Cancelled(event.identity, event.reason)
            }

            is TransitionEvent.Interrupt -> {
                WorkflowState.Interrupted(event.identity, event.reason)
            }

            is TransitionEvent.MarkEffectUnknown -> {
                WorkflowState.EffectUnknown(event.identity, event.reason)
            }
        }
    }

    private fun isMatchingIdentity(expected: OperationIdentity, actual: OperationIdentity): Boolean {
        return expected.taskId == actual.taskId &&
            expected.actionId == actual.actionId &&
            expected.generation == actual.generation
    }
}
