package br.com.srm.creditengine.domain.settlement;

import java.util.UUID;

/**
 * Guarda de idempotencia na borda da liquidacao.
 *
 * <p>Porta de saida, nao regra de negocio: a implementacao de producao e Redis
 * ({@code RedisIdempotencyStore}), e existe uma implementacao desligada
 * ({@code DisabledIdempotencyStore}) que responde sempre
 * {@link IdempotencyReservation.Status#UNAVAILABLE}.
 *
 * <p><b>O que esta porta e:</b> uma reserva barata, feita <i>antes</i> de resolver cotacao,
 * precificar e abrir transacao, para que o duplo clique e o retry de rede nao paguem o
 * trabalho caro nem pressionem o provedor externo.
 *
 * <p><b>O que esta porta nao e:</b> a autoridade sobre "este recebivel ja foi pago". Essa
 * continua sendo a mesma linha de {@code settlements}, com {@code uk_settlements_receivable}
 * e {@code uk_settlements_idempotency_key} commitados na mesma transacao do dinheiro.
 * Consequencia pratica e deliberada: <b>indisponibilidade do guarda nao pode derrubar a
 * liquidacao</b> - a implementacao responde {@code UNAVAILABLE} e o caso de uso segue pelo
 * caminho do banco.
 */
public interface IdempotencyStore {

    /**
     * Tenta reservar a chave para esta requisicao.
     *
     * <p>Contrato: nunca lanca por falha de infraestrutura. Guarda fora do ar devolve
     * {@link IdempotencyReservation.Status#UNAVAILABLE}.
     */
    IdempotencyReservation reserve(String idempotencyKey, String fingerprint);

    /**
     * Marca a chave como concluida.
     *
     * <p>Chamado <b>somente depois</b> de a transacao da liquidacao ter commitado: o que se
     * publica aqui e resultado consumado, nunca intencao.
     */
    void complete(IdempotencyReservation reservation, UUID settlementId);

    /**
     * Compensa a reserva de uma requisicao que nao gravou liquidacao.
     *
     * <p>E a linha que impede o pior modo de falha do desenho com Redis: sem ela, um
     * {@code 503} de cotacao deixaria a chave reservada sem pagamento nenhum, e o retry
     * legitimo do cliente receberia "duplicado" para dinheiro que nunca saiu.
     */
    void release(IdempotencyReservation reservation);
}
