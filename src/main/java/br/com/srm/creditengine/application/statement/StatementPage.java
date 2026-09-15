package br.com.srm.creditengine.application.statement;

import java.util.List;

/**
 * Pagina do extrato com o total de registros do filtro e os somatorios por moeda.
 *
 * @param totalElements quantidade de liquidacoes que atendem ao filtro (nao da pagina)
 * @param totals        somatorios calculados no banco sobre o filtro inteiro, nao sobre a
 *                      pagina: a mesa precisa do total do periodo, nao do total dos 20
 *                      primeiros
 */
public record StatementPage(
        List<StatementEntry> content,
        int page,
        int size,
        long totalElements,
        List<StatementTotal> totals
) {

    public int totalPages() {
        return (int) Math.ceilDiv(totalElements, size);
    }

    public boolean hasNext() {
        return (long) (page + 1) * size < totalElements;
    }
}
