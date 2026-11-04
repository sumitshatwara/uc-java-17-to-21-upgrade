package com.workshop.loanservice.service.exposure;

/** Exposure components handled by the calculator. */
public sealed interface KnownExposureComponent extends ExposureComponent
        permits OutstandingPrincipal, EscrowHolding, PaymentRecord, DelinquencyNotice {}
