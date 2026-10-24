#!/bin/sh
set -e
# Куда пересылать письма на чужие домены и с каким темпом — задаётся при запуске стенда:
# RELAYHOST=[smtp-stub]:2525 направляет почту в заглушку с пределом темпа, RATE_DELAY и CONCURRENCY
# ограничивают темп самого Postfix (базовая линия «темпом управляет почтовый сервер»)
if [ -n "$RELAYHOST" ]; then
  postconf -e "relayhost=$RELAYHOST"
fi
if [ -n "$RATE_DELAY" ]; then
  postconf -e "default_destination_rate_delay=$RATE_DELAY"
fi
if [ -n "$CONCURRENCY" ]; then
  postconf -e "default_destination_concurrency_limit=$CONCURRENCY"
fi
# Создаёт каталоги очереди: в один из них Dovecot кладёт сокет проверки учётных записей
postfix check
dovecot
exec postfix start-fg
