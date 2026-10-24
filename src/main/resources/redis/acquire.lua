-- Берёт по разрешению на каждый сервер получателя разом: либо на все, либо ни на один.
-- KEYS — состояния серверов (хэши: rate, tokens, ts). ARGV[1] — начальный темп, ARGV[2] — запас на залп, секунды.
-- Возвращает 0, если разрешения взяты, иначе — через сколько миллисекунд появится недостающее.
local time = redis.call('TIME')
local now = tonumber(time[1]) + tonumber(time[2]) / 1000000
local initialRate = tonumber(ARGV[1])
local burst = tonumber(ARGV[2])
local wait = 0
local states = {}

for i, key in ipairs(KEYS) do
    local state = redis.call('HMGET', key, 'rate', 'tokens', 'ts')
    local rate = tonumber(state[1]) or initialRate
    local capacity = math.max(1, rate * burst)
    -- Незнакомому серверу залп не положен: начинаем с одного письма
    local tokens = tonumber(state[2]) or 1
    local ts = tonumber(state[3]) or now
    tokens = math.min(capacity, tokens + math.max(0, now - ts) * rate)
    states[i] = { rate, tokens }
    if tokens < 1 then
        wait = math.max(wait, (1 - tokens) / rate)
    end
end

for i, key in ipairs(KEYS) do
    local tokens = states[i][2]
    if wait == 0 then
        tokens = tokens - 1
    end
    redis.call('HSET', key, 'rate', tostring(states[i][1]), 'tokens', tostring(tokens), 'ts', tostring(now))
    -- Состояние сервера, на который давно не писали, само исчезает
    redis.call('EXPIRE', key, 604800)
end

return math.ceil(wait * 1000)
