package br.com.srm.creditengine.application.statement;

/**
 * Porta de leitura do extrato analitico.
 *
 * <p>Separada dos repositorios de escrita: o extrato nao carrega agregado de dominio, nao
 * participa de transacao de negocio e evolui por necessidade de relatorio. A implementacao
 * usa JPA/JPQL (ver {@code JpaSettlementStatementQuery}).
 */
public interface SettlementStatementQuery {

    StatementPage findBy(StatementFilter filter);
}
