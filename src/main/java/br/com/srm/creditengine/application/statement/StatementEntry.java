package br.com.srm.creditengine.application.statement;

import br.com.srm.creditengine.domain.money.Money;
import br.com.srm.creditengine.domain.pricing.ReceivableType;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Linha do extrato: projecao de leitura, nao entidade.
 *
 * <p>Ela junta liquidacao, recebivel e cedente em um unico registro porque e assim que a
 * mesa le o extrato. Reconstruir agregados de dominio para depois achatar em relatorio
 * seria trabalho jogado fora - por isso o relatorio atalha da aplicacao direto para a
 * persistencia (enunciado, item 4.1.7).
 */
public record StatementEntry(
        UUID settlementId,
        UUID receivableId,
        UUID assignorId,
        String assignorDocument,
        String assignorLegalName,
        ReceivableType receivableType,
        LocalDate dueDate,
        int termMonths,
        Money faceValue,
        Money presentValue,
        Money discount,
        Money settlementAmount,
        BigDecimal fxRate,
        Instant settledAt
) {
}
