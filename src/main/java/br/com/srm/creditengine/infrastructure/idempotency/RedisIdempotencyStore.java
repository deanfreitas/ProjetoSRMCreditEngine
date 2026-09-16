package br.com.srm.creditengine.infrastructure.idempotency;

import br.com.srm.creditengine.domain.settlement.IdempotencyReservation;
import br.com.srm.creditengine.domain.settlement.IdempotencyStore;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * Guarda de idempotencia em Redis.
 *
 * <p>Uma chave por {@code Idempotency-Key}, com duas fases e TTLs diferentes:
 *
 * <ol>
 *   <li><b>{@code PROCESSING}</b>, escrito com {@code SET NX PX} na reserva. TTL curto de
 *       proposito: e o teto do prejuizo se o processo morrer entre a reserva e o commit;</li>
 *   <li><b>{@code COMPLETED}</b>, escrito <b>depois</b> do commit da transacao, com o id da
 *       liquidacao e TTL longo. Retry dentro da janela e respondido sem recalcular nada.</li>
 * </ol>
 *
 * <p>Tres propriedades explicam as decisoes deste codigo:
 *
 * <ul>
 *   <li><b>Falha aberto.</b> Toda operacao captura {@link DataAccessException} e degrada
 *       para {@code UNAVAILABLE}. Redis fora do ar deixa a liquidacao mais cara (volta o
 *       {@code SELECT} de idempotencia no banco), nunca indisponivel. Idempotencia de
 *       pagamento nao pode ser causa de incidente;</li>
 *   <li><b>Liberacao com dono.</b> A compensacao roda em Lua comparando o valor inteiro
 *       (estado, fingerprint e token). Um {@code DEL} cru apagaria a reserva de <i>outra</i>
 *       requisicao que pegou a mesma chave apos o TTL expirar;</li>
 *   <li><b>Nunca e a autoridade.</b> Perder esta chave (failover, AOF desligado, TTL
 *       vencido) custa desempenho, nao correcao: {@code uk_settlements_idempotency_key} e
 *       {@code uk_settlements_receivable} continuam no PostgreSQL, na mesma transacao do
 *       dinheiro.</li>
 * </ul>
 */
public class RedisIdempotencyStore implements IdempotencyStore {

    private static final Logger log = LoggerFactory.getLogger(RedisIdempotencyStore.class);

    private static final String PROCESSING = "PROCESSING";
    private static final String COMPLETED = "COMPLETED";
    private static final String SEPARATOR = "|";
    private static final String METRIC = "credit_engine.idempotency";

    /**
     * Compare-and-delete atomico: so apaga se o valor for exatamente o que esta reserva
     * escreveu. Sem o script, reserva e verificacao seriam duas chamadas com uma janela
     * entre elas - a mesma classe de bug que este guarda existe para evitar.
     */
    private static final RedisScript<Long> RELEASE_IF_OWNER = new DefaultRedisScript<>(
            "if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('DEL', KEYS[1]) else return 0 end",
            Long.class);

    private final StringRedisTemplate redis;
    private final MeterRegistry meterRegistry;
    private final String keyPrefix;
    private final Duration inProgressTtl;
    private final Duration completedTtl;

    public RedisIdempotencyStore(StringRedisTemplate redis,
                                 MeterRegistry meterRegistry,
                                 String keyPrefix,
                                 Duration inProgressTtl,
                                 Duration completedTtl) {
        this.redis = redis;
        this.meterRegistry = meterRegistry;
        this.keyPrefix = keyPrefix;
        this.inProgressTtl = inProgressTtl;
        this.completedTtl = completedTtl;
    }

    @Override
    public IdempotencyReservation reserve(String idempotencyKey, String fingerprint) {
        String token = UUID.randomUUID().toString();
        String key = redisKey(idempotencyKey);

        try {
            Boolean acquired = redis.opsForValue()
                    .setIfAbsent(key, value(PROCESSING, fingerprint, token), inProgressTtl);

            if (Boolean.TRUE.equals(acquired)) {
                count("acquired");
                return IdempotencyReservation.acquired(idempotencyKey, fingerprint, token);
            }

            String current = redis.opsForValue().get(key);
            if (current == null) {
                // Expirou entre o SET NX e o GET. Adivinhar estado aqui seria pior que
                // admitir ignorancia: deixa o PostgreSQL decidir.
                count("vanished");
                return IdempotencyReservation.unavailable(idempotencyKey, fingerprint);
            }
            return interpret(idempotencyKey, fingerprint, current);
        } catch (DataAccessException e) {
            log.warn("Guarda de idempotencia indisponivel na reserva; seguindo pelo PostgreSQL: "
                    + "idempotencyKey={} causa={}", idempotencyKey, e.getMostSpecificCause().toString());
            count("unavailable");
            return IdempotencyReservation.unavailable(idempotencyKey, fingerprint);
        }
    }

    @Override
    public void complete(IdempotencyReservation reservation, UUID settlementId) {
        try {
            // SET sem NX: quem chega aqui commitou o pagamento, e portanto e a verdade
            // mais recente sobre esta chave.
            redis.opsForValue().set(
                    redisKey(reservation.idempotencyKey()),
                    value(COMPLETED, reservation.fingerprint(), settlementId.toString()),
                    completedTtl);
            count("completed");
        } catch (DataAccessException e) {
            // Consequencia: o proximo retry desta chave paga o caminho do banco e e
            // respondido corretamente por ali. Nao ha risco de pagamento duplo.
            log.warn("Falha ao publicar conclusao no guarda de idempotencia: idempotencyKey={} settlementId={} causa={}",
                    reservation.idempotencyKey(), settlementId, e.getMostSpecificCause().toString());
            count("complete_failed");
        }
    }

    @Override
    public void release(IdempotencyReservation reservation) {
        if (!reservation.compensable()) {
            return;
        }

        try {
            redis.execute(
                    RELEASE_IF_OWNER,
                    List.of(redisKey(reservation.idempotencyKey())),
                    value(PROCESSING, reservation.fingerprint(), reservation.token()));
            count("released");
        } catch (DataAccessException e) {
            // Sem compensacao a chave fica presa ate o TTL de PROCESSING expirar - e por
            // isso que esse TTL e curto, e nao por economia de memoria.
            log.warn("Falha ao compensar reserva de idempotencia; a chave expira em {}: idempotencyKey={} causa={}",
                    inProgressTtl, reservation.idempotencyKey(), e.getMostSpecificCause().toString());
            count("release_failed");
        }
    }

    private IdempotencyReservation interpret(String idempotencyKey, String fingerprint, String current) {
        String[] parts = current.split("\\" + SEPARATOR, -1);
        String state = parts[0];
        String storedFingerprint = parts.length > 1 ? parts[1] : "";
        String payload = parts.length > 2 ? parts[2] : "";

        if (!storedFingerprint.equals(fingerprint)) {
            // Mesma chave, outro pedido. Erro do cliente, e o unico caso em que o guarda
            // recusa sozinho: devolver a liquidacao anterior seria responder outra coisa.
            count("conflict");
            return IdempotencyReservation.conflict(idempotencyKey, fingerprint);
        }

        if (COMPLETED.equals(state)) {
            count("replayed");
            return IdempotencyReservation.completed(idempotencyKey, fingerprint, parseId(payload));
        }

        count("in_progress");
        return IdempotencyReservation.inProgress(idempotencyKey, fingerprint);
    }

    private static UUID parseId(String payload) {
        try {
            return UUID.fromString(payload);
        } catch (IllegalArgumentException e) {
            // Valor gravado por versao anterior ou corrompido: nao e motivo para falhar a
            // requisicao, o id da liquidacao vem do banco na releitura.
            log.warn("Valor de conclusao sem settlementId utilizavel no guarda de idempotencia: '{}'", payload);
            return null;
        }
    }

    private String redisKey(String idempotencyKey) {
        return keyPrefix + idempotencyKey;
    }

    private static String value(String state, String fingerprint, String payload) {
        return state + SEPARATOR + fingerprint + SEPARATOR + payload;
    }

    private void count(String result) {
        meterRegistry.counter(METRIC, "store", "redis", "result", result).increment();
    }
}
