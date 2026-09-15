package br.com.srm.creditengine.support;

import br.com.srm.creditengine.domain.assignor.Assignor;
import br.com.srm.creditengine.domain.money.Currency;
import br.com.srm.creditengine.domain.money.Money;
import br.com.srm.creditengine.domain.pricing.ReceivableType;
import br.com.srm.creditengine.domain.receivable.Receivable;
import br.com.srm.creditengine.domain.receivable.ReceivableStatus;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** Massa de dados minima para os testes de integracao. */
public final class TestFixtures {

    private TestFixtures() {
    }

    public static Assignor assignor(String document) {
        return new Assignor(UUID.randomUUID(), document, "Cedente " + document, Instant.now());
    }

    public static Receivable receivable(UUID assignorId,
                                        ReceivableType type,
                                        String faceValue,
                                        Currency currency,
                                        LocalDate dueDate) {
        return new Receivable(
                UUID.randomUUID(),
                assignorId,
                type,
                Money.of(faceValue, currency),
                dueDate,
                ReceivableStatus.PENDING,
                0L);
    }
}
