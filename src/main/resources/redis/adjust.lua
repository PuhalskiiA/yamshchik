-- Подстраивает темп сервера получателя по его ответу. Выполняется атомарно, поэтому экземпляры сервиса,
-- пишущие одновременно, не затирают изменения друг друга.
-- KEYS[1] — состояние сервера (хэш: rate, tokens, ts, increasedAt, decreasedAt).
-- ARGV: 1 — сигнал (accepted | throttled), 2 — начальный темп, 3 — нижний предел, 4 — верхний предел,
-- 5 — шаг роста, 6 — интервал роста, секунды, 7 — множитель снижения, 8 — пауза между снижениями, секунды,
-- 9 — запас на залп, секунды.
-- Возвращает новый темп.
local time = redis.call('TIME')
local now = tonumber(time[1]) + tonumber(time[2]) / 1000000
local signal = ARGV[1]
local state = redis.call('HMGET', KEYS[1], 'rate', 'tokens', 'ts', 'increasedAt', 'decreasedAt')
local rate = tonumber(state[1]) or tonumber(ARGV[2])
local tokens = tonumber(state[2])
local ts = tonumber(state[3])
local increasedAt = tonumber(state[4])
local decreasedAt = tonumber(state[5])

-- Разрешения, накопленные до смены темпа, досчитываются по старому темпу
if tokens and ts then
    tokens = math.min(math.max(1, rate * tonumber(ARGV[9])), tokens + math.max(0, now - ts) * rate)
    redis.call('HSET', KEYS[1], 'tokens', tostring(tokens), 'ts', tostring(now))
end

if signal == 'throttled' then
    -- Отказы на письма, ушедшие до снижения, повторно темп не снижают
    if decreasedAt == nil or now - decreasedAt >= tonumber(ARGV[8]) then
        rate = math.max(tonumber(ARGV[3]), rate * tonumber(ARGV[7]))
        redis.call('HSET', KEYS[1], 'decreasedAt', tostring(now), 'increasedAt', tostring(now))
    end
elseif signal == 'accepted' then
    -- Отсчёт интервала роста начинается с первого принятого письма
    if increasedAt == nil then
        redis.call('HSET', KEYS[1], 'increasedAt', tostring(now))
    elseif now - increasedAt >= tonumber(ARGV[6]) then
        rate = math.min(tonumber(ARGV[4]), rate + tonumber(ARGV[5]))
        redis.call('HSET', KEYS[1], 'increasedAt', tostring(now))
    end
end

redis.call('HSET', KEYS[1], 'rate', tostring(rate))
redis.call('EXPIRE', KEYS[1], 604800)
return tostring(rate)
