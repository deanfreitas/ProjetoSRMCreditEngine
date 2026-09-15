package br.com.srm.creditengine.api.dto;

import br.com.srm.creditengine.application.statement.StatementEntry;
import br.com.srm.creditengine.application.statement.StatementPage;
import br.com.srm.creditengine.application.statement.StatementTotal;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Extrato paginado.
 *
 * <p>Nao usamos {@code Page} do Spring Data na fronteira: o contrato da API nao deveria
 * mudar porque a biblioteca de persistencia mudou (e o JSON do {@code Page} carrega campos
 * internos de paginacao que o cliente nao precisa).
 *
 * <p>{@code totals} vem do filtro inteiro, nao da pagina - a mesa precisa do total do
 * periodo.
 */
@Schema(name = "SettlementStatement")
public record StatementView(
        List<Entry> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean hasNext,
        List<Total> totals
) {

    public static StatementView of(StatementPage statementPage) {
        return new StatementView(
                statementPage.content().stream().map(Entry::of).toList(),
                statementPage.page(),
                statementPage.size(),
                statementPage.totalElements(),
                statementPage.totalPages(),
                statementPage.hasNext(),
                statementPage.totals().stream().map(Total::of).toList());
    }

    @Schema(name = "SettlementStatementEntry")
    public record Entry(
            UUID settlementId,
            UUID receivableId,
            UUID assignorId,
            String assignorDocument,
            String assignorLegalName,
            String receivableType,
            LocalDate dueDate,
            int termMonths,
            MoneyView faceValue,
            MoneyView presentValue,
            MoneyView discount,
            MoneyView settlementAmount,
            String fxRate,
            Instant settledAt
    ) {

        static Entry of(StatementEntry entry) {
            return new Entry(
                    entry.settlementId(),
                    entry.receivableId(),
                    entry.assignorId(),
                    entry.assignorDocument(),
                    entry.assignorLegalName(),
                    entry.receivableType().name(),
                    entry.dueDate(),
                    entry.termMonths(),
                    MoneyView.of(entry.faceValue()),
                    MoneyView.of(entry.presentValue()),
                    MoneyView.of(entry.discount()),
                    MoneyView.of(entry.settlementAmount()),
                    entry.fxRate() == null ? null : entry.fxRate().toPlainString(),
                    entry.settledAt());
        }
    }

    @Schema(name = "SettlementStatementTotal", description = "Somatorio por par de moedas (face, liquidacao)")
    public record Total(
            long settlements,
            MoneyView faceValue,
            MoneyView presentValue,
            MoneyView discount,
            MoneyView settlementAmount
    ) {

        static Total of(StatementTotal total) {
            return new Total(
                    total.settlements(),
                    MoneyView.of(total.faceValue()),
                    MoneyView.of(total.presentValue()),
                    MoneyView.of(total.discount()),
                    MoneyView.of(total.settlementAmount()));
        }
    }
}
