package ru.yamshchik.yamshchik.application.service.ratelimit;

import ru.yamshchik.yamshchik.config.properties.RateLimitProperties;
import ru.yamshchik.yamshchik.domain.TrafficClass;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;


/**
 * Ограничение темпа корзиной разрешений. Общая корзина наполняется с полным темпом: из неё берёт каждое
 * письмо, поэтому общий темп и залп не превышают заданных. Массовые письма, пока срочные ждут отправки,
 * берут ещё и из своей корзины, наполняемой с долей темпа массовых, — так срочным остаётся их доля.
 * Когда срочных нет, массовые берут только из общей корзины и занимают весь темп.
 */
public abstract class TokenBucketRateLimiter implements SendRateLimiter {

    // В корзине всегда помещается хотя бы одно письмо, иначе при малом темпе не уйдёт ни одно
    private static final double MIN_CAPACITY = 1;

    private static final double NANOS_PER_SECOND = 1_000_000_000d;

    private final double urgentShare;

    private final double burstSeconds;

    // Первое письмо после старта уходит сразу
    private double tokens = MIN_CAPACITY;

    private double bulkTokens = MIN_CAPACITY;

    private Instant refilledAt;

    protected TokenBucketRateLimiter(RateLimitProperties properties) {
        this.urgentShare = properties.getUrgentShare();
        this.burstSeconds = properties.getBurst().toNanos() / NANOS_PER_SECOND;
    }

    @Override
    public synchronized Optional<Permit> tryAcquire(TrafficClass trafficClass, boolean urgentIdle, Instant now) {
        refill(now);
        if (tokens < 1) {
            return Optional.empty();
        }
        boolean limitedToShare = trafficClass == TrafficClass.BULK && !urgentIdle;
        if (limitedToShare && bulkTokens < 1) {
            return Optional.empty();
        }
        tokens -= 1;
        if (limitedToShare) {
            bulkTokens -= 1;
            return Optional.of(new Permit(TrafficClass.BULK));
        }
        return Optional.of(new Permit(TrafficClass.URGENT));
    }

    @Override
    public synchronized void release(Permit permit) {
        tokens = Math.min(capacity(currentRate()), tokens + 1);
        if (permit.share() == TrafficClass.BULK) {
            bulkTokens = Math.min(capacity(bulkRate()), bulkTokens + 1);
        }
    }

    @Override
    public synchronized double rate() {
        return currentRate();
    }

    @Override
    public synchronized Duration timeUntilPermit(Instant now) {
        refill(now);
        double missing = Math.max(0, 1 - tokens);
        return Duration.ofNanos((long) Math.ceil(missing / currentRate() * NANOS_PER_SECOND));
    }

    /**
     * @return текущий общий темп, писем в секунду
     */
    protected abstract double currentRate();

    /**
     * Досчитывает накопленные разрешения по старому темпу. Вызывается перед сменой темпа,
     * чтобы новый темп не применился задним числом.
     */
    protected synchronized void refill(Instant now) {
        if (refilledAt != null && now.isAfter(refilledAt)) {
            double seconds = Duration.between(refilledAt, now).toNanos() / NANOS_PER_SECOND;
            tokens = Math.min(capacity(currentRate()), tokens + seconds * currentRate());
            bulkTokens = Math.min(capacity(bulkRate()), bulkTokens + seconds * bulkRate());
        }
        if (refilledAt == null || now.isAfter(refilledAt)) {
            refilledAt = now;
        }
    }

    private double bulkRate() {
        return currentRate() * (1 - urgentShare);
    }

    private double capacity(double rate) {
        return Math.max(MIN_CAPACITY, rate * burstSeconds);
    }
}
