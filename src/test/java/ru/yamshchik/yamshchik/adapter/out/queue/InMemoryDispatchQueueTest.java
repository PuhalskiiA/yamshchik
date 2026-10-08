package ru.yamshchik.yamshchik.adapter.out.queue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.yamshchik.yamshchik.TestData;
import ru.yamshchik.yamshchik.application.port.out.EmailRepository;
import ru.yamshchik.yamshchik.domain.Email;
import ru.yamshchik.yamshchik.domain.EmailId;
import ru.yamshchik.yamshchik.domain.EmailStatus;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static ru.yamshchik.yamshchik.TestData.LEASE;
import static ru.yamshchik.yamshchik.TestData.NOW;


@ExtendWith(MockitoExtension.class)
@DisplayName("Очередь отправки в памяти")
class InMemoryDispatchQueueTest {

    @Mock
    private EmailRepository emailRepository;

    private InMemoryDispatchQueue queue;

    @BeforeEach
    void setUp() {
        queue = new InMemoryDispatchQueue(emailRepository);
    }

    @Test
    @DisplayName("Пустая очередь ничего не отдаёт")
    void emptyQueue() {
        assertThat(queue.claimNext(NOW, LEASE)).isEmpty();
        verifyNoInteractions(emailRepository);
    }

    @Test
    @DisplayName("Захват переводит письмо в отправку и записывает это в хранилище")
    void claimStartsSending() {
        Email email = stored(TestData.acceptedEmail());
        queue.enqueue(email.id(), NOW);

        Email claimed = queue.claimNext(NOW, LEASE).orElseThrow();

        assertThat(claimed.state().status())
                .overridingErrorMessage("Захваченное письмо должно быть в статусе SENDING, а не %s",
                                        claimed.state().status())
                .isEqualTo(EmailStatus.SENDING);
        assertThat(claimed.state().attempts()).isEqualTo(1);
        verify(emailRepository).updateState(claimed.state());
    }

    @Test
    @DisplayName("Одно письмо не отдаётся дважды")
    void emailIsClaimedOnce() {
        Email email = stored(TestData.acceptedEmail());
        queue.enqueue(email.id(), NOW);

        queue.claimNext(NOW, LEASE);

        assertThat(queue.claimNext(NOW, LEASE))
                .overridingErrorMessage("После захвата письма в очереди ничего не должно остаться")
                .isEmpty();
    }

    @Test
    @DisplayName("Письмо не отдаётся раньше своего времени")
    void notBeforeAvailableAt() {
        Email email = stored(TestData.acceptedEmail());
        queue.enqueue(email.id(), NOW.plusSeconds(30));

        assertThat(queue.claimNext(NOW.plusSeconds(29), LEASE))
                .overridingErrorMessage("Отложенное письмо не должно отдаваться до времени следующей попытки")
                .isEmpty();
        assertThat(queue.claimNext(NOW.plusSeconds(30), LEASE)).isPresent();
    }

    @Test
    @DisplayName("Письма отдаются по времени доступности, при равном времени — в порядке постановки")
    void order() {
        Email late = stored(TestData.acceptedEmail());
        Email first = stored(TestData.acceptedEmail());
        Email second = stored(TestData.acceptedEmail());
        queue.enqueue(late.id(), NOW.plusSeconds(10));
        queue.enqueue(first.id(), NOW);
        queue.enqueue(second.id(), NOW);

        EmailId claimedFirst = queue.claimNext(NOW.plusSeconds(60), LEASE).orElseThrow().id();
        EmailId claimedSecond = queue.claimNext(NOW.plusSeconds(60), LEASE).orElseThrow().id();
        EmailId claimedThird = queue.claimNext(NOW.plusSeconds(60), LEASE).orElseThrow().id();

        assertThat(claimedFirst)
                .overridingErrorMessage("Первым должно уйти письмо, поставленное раньше при том же времени")
                .isEqualTo(first.id());
        assertThat(claimedSecond).isEqualTo(second.id());
        assertThat(claimedThird)
                .overridingErrorMessage("Письмо с более поздним временем доступности должно уйти последним")
                .isEqualTo(late.id());
    }

    @Test
    @DisplayName("Письмо, которого нет в хранилище, пропускается")
    void missingEmailIsSkipped() {
        EmailId missing = EmailId.newId();
        when(emailRepository.find(missing)).thenReturn(Optional.empty());
        Email present = stored(TestData.acceptedEmail());
        queue.enqueue(missing, NOW);
        queue.enqueue(present.id(), NOW);

        Optional<Email> claimed = queue.claimNext(NOW, LEASE);

        assertThat(claimed).map(Email::id)
                .overridingErrorMessage("Пропавшее письмо не должно останавливать очередь")
                .contains(present.id());
    }

    private Email stored(Email email) {
        when(emailRepository.find(email.id())).thenReturn(Optional.of(email));
        return email;
    }
}
