package com.github.xingjianluo.previewenvorchestratorjava.domain;

/**
 * Why a state transition happened. Manual and automatic (lease) reasons are
 * distinct so the resulting status is always explainable.
 */
public enum TransitionReason {
    CREATE_REQUESTED,
    CREATE_FAILED,
    START_REQUESTED,
    START_FAILED,
    STOP_REQUESTED,
    STOP_FAILED,
    DESTROY_REQUESTED,
    DESTROY_FAILED,
    IDLE_STOP_LEASE,
    IDLE_DESTROY_LEASE
}
