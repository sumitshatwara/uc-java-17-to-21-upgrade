package com.workshop.loanservice.service;

import com.workshop.loanservice.dto.BorrowerExposureDto;
import com.workshop.loanservice.entity.LegacyBorrower;
import com.workshop.loanservice.entity.LegacyLoanAccount;
import com.workshop.loanservice.entity.LegacyPayment;
import com.workshop.loanservice.repository.LegacyBorrowerRepository;
import com.workshop.loanservice.repository.LegacyLoanAccountRepository;
import com.workshop.loanservice.repository.LegacyPaymentRepository;
import com.workshop.loanservice.service.exposure.DelinquencyNotice;
import com.workshop.loanservice.service.exposure.EscrowHolding;
import com.workshop.loanservice.service.exposure.ExposureCalculator;
import com.workshop.loanservice.service.exposure.ExposureComponent;
import com.workshop.loanservice.service.exposure.ExposureTotals;
import com.workshop.loanservice.service.exposure.OutstandingPrincipal;
import com.workshop.loanservice.service.exposure.PaymentRecord;
import java.net.MalformedURLException;
import java.net.URI;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

/**
 * Builds a borrower's exposure by fanning out blocking reads (borrower, loans, then payments per
 * loan) on a fixed-size thread pool. Each read carries a simulated remote-call delay.
 */
@Service
public class ExposureService implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(ExposureService.class);
    private static final Locale UK = Locale.of("en", "GB");

    private final LegacyBorrowerRepository borrowerRepository;
    private final LegacyLoanAccountRepository loanAccountRepository;
    private final LegacyPaymentRepository paymentRepository;
    private final ThreadPoolTaskExecutor executor;
    private final long simulatedLatencyMs;
    private final String publicBaseUrl;

    public ExposureService(LegacyBorrowerRepository borrowerRepository,
                           LegacyLoanAccountRepository loanAccountRepository,
                           LegacyPaymentRepository paymentRepository,
                           @Value("${loan.exposure.pool-size:4}") int poolSize,
                           @Value("${loan.exposure.simulated-latency-ms:50}") long simulatedLatencyMs,
                           @Value("${loan.public-base-url:https://loans.example.com}") String publicBaseUrl) {
        this.borrowerRepository = borrowerRepository;
        this.loanAccountRepository = loanAccountRepository;
        this.paymentRepository = paymentRepository;
        this.simulatedLatencyMs = simulatedLatencyMs;
        this.publicBaseUrl = publicBaseUrl;
        this.executor = new ThreadPoolTaskExecutor();
        this.executor.setCorePoolSize(poolSize);
        this.executor.setMaxPoolSize(poolSize);
        this.executor.setQueueCapacity(10_000);
        this.executor.setThreadNamePrefix("exposure-");
        this.executor.initialize();
    }

    public BorrowerExposureDto getExposure(String borrowerId) {
        CompletableFuture<LegacyBorrower> borrowerFuture =
                fetch(() -> borrowerRepository.findById(borrowerId).orElse(null));
        CompletableFuture<List<LegacyLoanAccount>> loansFuture =
                fetch(() -> loanAccountRepository.findByBorrowerId(borrowerId));

        LegacyBorrower borrower = borrowerFuture.join();
        List<LegacyLoanAccount> loans = loansFuture.join();
        if (borrower == null) {
            throw new RuntimeException("Borrower not found: " + borrowerId);
        }

        List<CompletableFuture<List<LegacyPayment>>> paymentFutures = loans.stream()
                .map(loan -> fetch(() -> paymentRepository.findByLoanAccountNumber(loan.getLoanAccountNumber())))
                .collect(Collectors.toList());

        List<ExposureComponent> components = new ArrayList<>();
        for (LegacyLoanAccount loan : loans) {
            components.add(new OutstandingPrincipal(loan.getLoanAccountNumber(),
                    LegacyFieldParser.parseAmount(loan.getOriginalAmount()),
                    LegacyFieldParser.parseAmount(loan.getCurrentBalance())));
            components.add(new EscrowHolding(LegacyFieldParser.parseAmount(loan.getEscrowBalance())));
            Integer daysPastDue = LegacyFieldParser.parseInteger(loan.getDelinquencyDays());
            if (daysPastDue != null && daysPastDue > 0) {
                components.add(new DelinquencyNotice(daysPastDue));
            }
        }
        for (CompletableFuture<List<LegacyPayment>> paymentFuture : paymentFutures) {
            for (LegacyPayment payment : paymentFuture.join()) {
                components.add(new PaymentRecord(LegacyFieldParser.parseDate(payment.getPaymentDate()),
                        LegacyFieldParser.parseAmount(payment.getTotalAmount()),
                        LegacyFieldParser.parseAmount(payment.getLateFee())));
            }
        }

        ExposureTotals totals = ExposureCalculator.summarize(components);
        Integer creditScore = LegacyFieldParser.parseInteger(borrower.getCreditScore());

        BorrowerExposureDto dto = new BorrowerExposureDto();
        dto.setBorrowerId(borrower.getBorrowerId());
        dto.setBorrowerName(borrower.getFirstName() + " " + borrower.getLastName());
        dto.setCreditScore(creditScore);
        dto.setLoanCount(loans.size());
        dto.setTotalOriginalAmount(totals.getTotalOriginalAmount());
        dto.setTotalOutstandingBalance(totals.getTotalOutstandingBalance());
        dto.setTotalEscrowBalance(totals.getTotalEscrowBalance());
        dto.setTotalPaid(totals.getTotalPaid());
        dto.setTotalLateFees(totals.getTotalLateFees());
        dto.setMaxDaysPastDue(totals.getMaxDaysPastDue());
        dto.setLastPaymentDate(totals.getLastPaymentDate() != null ? totals.getLastPaymentDate().toString() : null);
        dto.setRiskBand(ExposureCalculator.riskBand(totals, creditScore));
        dto.setOutstandingBalanceDisplay(NumberFormat.getCurrencyInstance(UK).format(totals.getTotalOutstandingBalance()));
        dto.setSelfLink(selfLink(borrower.getBorrowerId()));
        return dto;
    }

    private <T> CompletableFuture<T> fetch(Supplier<T> call) {
        return CompletableFuture.supplyAsync(() -> {
            log.debug("exposure read on thread {}", Thread.currentThread().threadId());
            simulateRemoteCall();
            return call.get();
        }, executor);
    }

    private void simulateRemoteCall() {
        if (simulatedLatencyMs <= 0) {
            return;
        }
        try {
            Thread.sleep(simulatedLatencyMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted during exposure read", e);
        }
    }

    private String selfLink(String borrowerId) {
        try {
            return URI.create(publicBaseUrl + "/api/borrowers/" + borrowerId + "/exposure")
                    .toURL().toString();
        } catch (MalformedURLException | IllegalArgumentException e) {
            throw new IllegalStateException("Bad public base URL: " + publicBaseUrl, e);
        }
    }

    @Override
    public void destroy() {
        executor.shutdown();
    }
}
