package br.com.srm.creditengine.domain.receivable;

import br.com.srm.creditengine.domain.DomainException;

import java.util.UUID;

/**
 * Duas liquidacoes simultaneas do mesmo recebivel: o optimistic locking detectou o
 * conflito e esta transacao perdeu. A resposta correta ao cliente e {@code 409}, nunca
 * "segue o jogo" - perder esta transacao significa que a outra ja pagou.
 */
public class ConcurrentSettlementException extends DomainException {

    public ConcurrentSettlementException(UUID receivableId, Throwable cause) {
        super("Liquidacao concorrente detectada para o recebivel " + receivableId, cause);
    }

    @Override
    public String errorCode() {
        return "CONCURRENT_SETTLEMENT";
    }
}
