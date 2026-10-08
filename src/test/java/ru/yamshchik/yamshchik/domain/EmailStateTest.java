package ru.yamshchik.yamshchik.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static ru.yamshchik.yamshchik.TestData.LEASE;
import static ru.yamshchik.yamshchik.TestData.NOW;


@DisplayName("Состояние письма и переходы статусов")
class EmailStateTest {

    private static final EmailId ID = EmailId.newId();

    @Test
    @DisplayName("Принятое письмо сразу доступно для отправки")
    void acceptedIsAvailableImmediately() {
        EmailState state = EmailState.accepted(ID, NOW);

        assertThat(state.status())
                .overridingErrorMessage("Принятое письмо должно быть в статусе ACCEPTED, а не %s", state.status())
                .isEqualTo(EmailStatus.ACCEPTED);
        assertThat(state.attempts())
                .overridingErrorMessage("У принятого письма ещё не должно быть попыток отправки")
                .isZero();
        assertThat(state.availableAt())
                .overridingErrorMessage("Принятое письмо должно быть доступно с момента приёма")
                .isEqualTo(NOW);
        assertThat(state.createdAt()).isEqualTo(NOW);
        assertThat(state.updatedAt()).isEqualTo(NOW);
        assertThat(state.sentAt()).isNull();
    }

    @Test
    @DisplayName("Захват увеличивает счётчик попыток и ставит срок аренды")
    void startSendingCountsAttemptAndSetsLease() {
        Instant claimedAt = NOW.plusSeconds(10);

        EmailState sending = EmailState.accepted(ID, NOW).startSending(claimedAt, LEASE);

        assertThat(sending.status()).isEqualTo(EmailStatus.SENDING);
        assertThat(sending.attempts())
                .overridingErrorMessage("Захват должен засчитываться как попытка отправки")
                .isEqualTo(1);
        assertThat(sending.availableAt())
                .overridingErrorMessage("До конца аренды письмо не должно быть доступно другим обработчикам")
                .isEqualTo(claimedAt.plus(LEASE));
        assertThat(sending.updatedAt()).isEqualTo(claimedAt);
        assertThat(sending.createdAt())
                .overridingErrorMessage("Время приёма письма не должно меняться при переходах")
                .isEqualTo(NOW);
    }

    @Test
    @DisplayName("Успешная отправка — конечное состояние без срока доступности")
    void sentIsTerminal() {
        Instant sentAt = NOW.plusSeconds(2);

        EmailState sent = sending().sent(sentAt);

        assertThat(sent.status()).isEqualTo(EmailStatus.SENT);
        assertThat(sent.sentAt()).isEqualTo(sentAt);
        assertThat(sent.availableAt())
                .overridingErrorMessage("Отправленное письмо не должно возвращаться в очередь")
                .isNull();
        assertThat(sent.lastError())
                .overridingErrorMessage("После успеха ошибка прошлой попытки должна быть сброшена")
                .isNull();
    }

    @Test
    @DisplayName("Временный отказ откладывает письмо до следующей попытки")
    void deferredWaitsForNextAttempt() {
        Instant nextAttemptAt = NOW.plusSeconds(30);

        EmailState deferred = sending().deferred("451 try later", NOW.plusSeconds(1), nextAttemptAt);

        assertThat(deferred.status()).isEqualTo(EmailStatus.DEFERRED);
        assertThat(deferred.lastError()).isEqualTo("451 try later");
        assertThat(deferred.availableAt())
                .overridingErrorMessage("Отложенное письмо должно стать доступным в момент следующей попытки")
                .isEqualTo(nextAttemptAt);
        assertThat(deferred.attempts())
                .overridingErrorMessage("Откладывание не должно менять число сделанных попыток")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("Окончательный отказ — конечное состояние с причиной")
    void failedIsTerminal() {
        EmailState failed = sending().failed("550 no such user", NOW.plusSeconds(1));

        assertThat(failed.status()).isEqualTo(EmailStatus.FAILED);
        assertThat(failed.lastError()).isEqualTo("550 no such user");
        assertThat(failed.availableAt())
                .overridingErrorMessage("Письмо с окончательным отказом не должно возвращаться в очередь")
                .isNull();
        assertThat(failed.sentAt()).isNull();
    }

    @Test
    @DisplayName("Отложенное письмо берётся в отправку, когда подошло время")
    void deferredCanBeClaimedWhenDue() {
        Instant nextAttemptAt = NOW.plusSeconds(30);
        EmailState deferred = sending().deferred("451", NOW, nextAttemptAt);

        EmailState sending = deferred.startSending(nextAttemptAt, LEASE);

        assertThat(sending.attempts())
                .overridingErrorMessage("Повторный захват должен быть второй попыткой")
                .isEqualTo(2);
        assertThat(sending.lastError())
                .overridingErrorMessage("Причина прошлого отказа должна сохраняться до исхода новой попытки")
                .isEqualTo("451");
    }

    @Test
    @DisplayName("Отложенное письмо нельзя взять раньше срока")
    void deferredCannotBeClaimedEarly() {
        EmailState deferred = sending().deferred("451", NOW, NOW.plusSeconds(30));

        assertThatThrownBy(() -> deferred.startSending(NOW.plusSeconds(29), LEASE))
                .overridingErrorMessage("Захват до времени следующей попытки должен быть запрещён")
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("Письмо в отправке нельзя взять, пока аренда не истекла")
    void sendingCannotBeClaimedWithinLease() {
        EmailState sending = sending();

        assertThatThrownBy(() -> sending.startSending(NOW.plus(LEASE).minusMillis(1), LEASE))
                .overridingErrorMessage("Письмо не должно доставаться второму обработчику до конца аренды")
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("Брошенное письмо берётся повторно после истечения аренды")
    void sendingCanBeReclaimedAfterLease() {
        Instant afterLease = NOW.plus(LEASE);

        EmailState reclaimed = sending().startSending(afterLease, Duration.ofMinutes(1));

        assertThat(reclaimed.status()).isEqualTo(EmailStatus.SENDING);
        assertThat(reclaimed.attempts())
                .overridingErrorMessage("Повторный захват брошенного письма должен считаться новой попыткой")
                .isEqualTo(2);
        assertThat(reclaimed.availableAt()).isEqualTo(afterLease.plus(Duration.ofMinutes(1)));
    }

    @ParameterizedTest
    @EnumSource(value = EmailStatus.class, names = {"SENT", "FAILED"})
    @DisplayName("Из конечного статуса письмо в отправку не берётся")
    void terminalCannotBeClaimed(EmailStatus terminal) {
        EmailState state = terminal == EmailStatus.SENT ? sending().sent(NOW) : sending().failed("x", NOW);

        assertThatThrownBy(() -> state.startSending(NOW.plusSeconds(1), LEASE))
                .overridingErrorMessage("Письмо в статусе %s не должно отправляться повторно", terminal)
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("Итог попытки можно записать только письму в отправке")
    void resultRequiresSending() {
        EmailState accepted = EmailState.accepted(ID, NOW);

        assertThatThrownBy(() -> accepted.sent(NOW)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> accepted.failed("x", NOW)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> accepted.deferred("x", NOW, NOW.plusSeconds(1)))
                .overridingErrorMessage("Нельзя отложить письмо, которое ещё не брали в отправку")
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("Отложить письмо без времени следующей попытки нельзя")
    void deferredRequiresNextAttempt() {
        EmailState sending = sending();

        assertThatThrownBy(() -> sending.deferred("451", NOW, null)).isInstanceOf(NullPointerException.class);
    }

    private static EmailState sending() {
        return EmailState.accepted(ID, NOW).startSending(NOW, LEASE);
    }
}
