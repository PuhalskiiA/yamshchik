-- Подстраивает темп сервера получателя по оценке его пропускной способности. Сервер сам не говорит свой предел,
-- но видно, сколько писем он реально принимает: принятые письма считаются окнами, суммарно по всем экземплярам
-- сервиса, и темп держится по этой оценке.
--   startup — темп удваивается каждое окно, пока сервер не попросит сбавить;
--   cruise  — темп равен оценке; через несколько окон — проба;
--   probe   — одно окно с темпом выше оценки: если сервер принял больше, оценка растёт.
-- Отказ во время пробы значит лишь, что проба зашла выше предела: темп возвращается к оценке. В остальное время
-- одиночный отказ считается случайным (экземпляры отправляют не строго по часам), и только отказы в двух окнах
-- подряд ставят оценку по наибольшему числу писем, принятых за последние окна, с запасом, а не делят темп пополам. Наибольшее, а не последнее: принятое в одном окне ограничено нашим же темпом, и по последнему окну
-- оценка с каждым отказом скатывалась бы всё ниже.
--
-- KEYS[1] — состояние сервера (хэш). ARGV: 1 — сигнал (accepted | throttled), 2 — начальный темп,
-- 3 — нижний предел, 4 — верхний предел, 5 — запас на залп, секунды, 6 — наименьшее окно, секунды,
-- 7 — сколько писем должно уместиться в окно, 8 — множитель пробы, 9 — окон между пробами,
-- 10 — запас под пределом после просьбы сбавить (множитель меньше единицы),
-- 11 — за сколько последних окон помнится наибольшее число принятых писем.
-- Возвращает новый темп.
local time = redis.call('TIME')
local now = tonumber(time[1]) + tonumber(time[2]) / 1000000
local signal = ARGV[1]
local minRate = tonumber(ARGV[3])
local maxRate = tonumber(ARGV[4])
local burst = tonumber(ARGV[5])
local minWindow = tonumber(ARGV[6])
local windowMessages = tonumber(ARGV[7])
local probeGain = tonumber(ARGV[8])
local cruiseWindows = tonumber(ARGV[9])
local backoff = tonumber(ARGV[10])
local historySize = tonumber(ARGV[11])

local h = redis.call('HMGET', KEYS[1], 'rate', 'tokens', 'ts', 'estimate', 'phase', 'windowStart',
                     'windowAccepted', 'lastWindowRate', 'cruiseLeft', 'throttled', 'history', 'strikes')
local rate = tonumber(h[1]) or tonumber(ARGV[2])
local tokens = tonumber(h[2])
local ts = tonumber(h[3])
local estimate = tonumber(h[4])
local phase = h[5] or 'startup'
local windowStart = tonumber(h[6]) or now
local windowAccepted = tonumber(h[7]) or 0
local lastWindowRate = tonumber(h[8])
local cruiseLeft = tonumber(h[9]) or 0
local throttled = h[10] == '1'
local strikes = tonumber(h[12]) or 0
local history = {}
for value in string.gmatch(h[11] or '', '[^,]+') do
    history[#history + 1] = tonumber(value)
end

local function bestRate()
    local best = 0
    for _, value in ipairs(history) do
        best = math.max(best, value)
    end
    return best
end

-- Разрешения, накопленные до смены темпа, досчитываются по старому темпу
if tokens and ts then
    tokens = math.min(math.max(1, rate * burst), tokens + math.max(0, now - ts) * rate)
    redis.call('HSET', KEYS[1], 'tokens', tostring(tokens), 'ts', tostring(now))
end

if signal == 'accepted' then
    windowAccepted = windowAccepted + 1
end

local elapsed = now - windowStart
local window = math.max(minWindow, windowMessages / rate)

if signal == 'throttled' and not throttled then
    -- Один отказ на окно: остальные отказы того же окна — следствие того же превышения
    throttled = true
    local measured = 0
    if elapsed >= minWindow / 2 then
        measured = windowAccepted / elapsed
    end
    if phase == 'probe' then
        rate = math.max(minRate, math.min(maxRate, estimate or rate))
        phase = 'cruise'
        cruiseLeft = cruiseWindows
    else
        strikes = strikes + 1
        if phase == 'startup' or strikes >= 2 then
            local capacity = math.max(measured, bestRate())
            if capacity <= 0 then
                -- Принятых писем ещё нет: мерить нечего, отступаем как AIMD
                capacity = rate / 2
            end
            estimate = math.max(minRate, math.min(maxRate, capacity * backoff))
            rate = estimate
            phase = 'cruise'
            cruiseLeft = cruiseWindows
            strikes = 0
        end
    end
    windowStart = now
    windowAccepted = 0
elseif elapsed >= window then
    local measured = windowAccepted / elapsed
    lastWindowRate = measured
    history[#history + 1] = measured
    while #history > historySize do
        table.remove(history, 1)
    end
    if not throttled then
        if phase == 'startup' then
            estimate = math.max(estimate or 0, measured)
            rate = math.min(maxRate, rate * 2)
        elseif phase == 'probe' then
            -- Проба прошла без отказов: сервер принимает не меньше измеренного
            estimate = math.max(estimate or 0, measured)
            rate = math.max(minRate, math.min(maxRate, estimate))
            phase = 'cruise'
            cruiseLeft = cruiseWindows
        else
            cruiseLeft = cruiseLeft - 1
            if cruiseLeft <= 0 then
                phase = 'probe'
                rate = math.min(maxRate, (estimate or rate) * probeGain)
            end
        end
    end
    if not throttled then
        strikes = 0
    end
    windowStart = now
    windowAccepted = 0
    throttled = false
end

redis.call('HSET', KEYS[1], 'rate', tostring(rate), 'phase', phase, 'windowStart', tostring(windowStart),
           'windowAccepted', tostring(windowAccepted), 'cruiseLeft', tostring(cruiseLeft),
           'throttled', throttled and '1' or '0', 'strikes', tostring(strikes))
if estimate then
    redis.call('HSET', KEYS[1], 'estimate', tostring(estimate))
end
if lastWindowRate then
    redis.call('HSET', KEYS[1], 'lastWindowRate', tostring(lastWindowRate))
end
local historyValues = {}
for i, value in ipairs(history) do
    historyValues[i] = tostring(value)
end
redis.call('HSET', KEYS[1], 'history', table.concat(historyValues, ','))
redis.call('EXPIRE', KEYS[1], 604800)
return tostring(rate)
