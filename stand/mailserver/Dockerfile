# Почтовый сервер стенда: Postfix принимает письма от сервиса и шлёт отчёты о доставке,
# Dovecot проверяет учётные записи и отдаёт ящики по IMAP.
# Версия Alpine закреплена ради Dovecot 2.3: с 2.4 формат его настроек другой
FROM alpine:3.20
RUN apk add --no-cache postfix dovecot \
    && addgroup -g 5000 mailboxes \
    && adduser -D -H -u 5000 -G mailboxes mailboxes \
    && mkdir -p /var/mail/vhosts \
    && chown -R mailboxes:mailboxes /var/mail/vhosts
COPY main.cf vmailbox /etc/postfix/
COPY dovecot.conf users /etc/dovecot/
COPY entrypoint.sh /entrypoint.sh
EXPOSE 25 143
ENTRYPOINT ["sh", "/entrypoint.sh"]
