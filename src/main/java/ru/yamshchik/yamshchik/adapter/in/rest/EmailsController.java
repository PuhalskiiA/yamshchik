package ru.yamshchik.yamshchik.adapter.in.rest;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import ru.yamshchik.yamshchik.adapter.in.rest.api.EmailsApi;
import ru.yamshchik.yamshchik.adapter.in.rest.dto.EmailRequestDto;
import ru.yamshchik.yamshchik.adapter.in.rest.dto.EmailStateDto;
import ru.yamshchik.yamshchik.application.port.in.GetEmailStateUseCase;
import ru.yamshchik.yamshchik.application.port.in.SubmitEmailUseCase;
import ru.yamshchik.yamshchik.config.exception.type.EmailNotFoundException;
import ru.yamshchik.yamshchik.domain.EmailId;
import ru.yamshchik.yamshchik.domain.EmailState;

import java.util.List;
import java.util.UUID;


@RestController
@RequiredArgsConstructor
public class EmailsController implements EmailsApi {

    private final SubmitEmailUseCase submitEmailUseCase;

    private final GetEmailStateUseCase getEmailStateUseCase;

    private final EmailRestMapper mapper;

    @Override
    public ResponseEntity<EmailStateDto> submitEmail(EmailRequestDto email, List<MultipartFile> attachments) {
        EmailState state = submitEmailUseCase.submit(mapper.toCommand(email, attachments));
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(mapper.toDto(state));
    }

    @Override
    public ResponseEntity<EmailStateDto> getEmail(UUID emailId) {
        return getEmailStateUseCase.getState(new EmailId(emailId))
                .map(mapper::toDto)
                .map(ResponseEntity::ok)
                .orElseThrow(() -> new EmailNotFoundException(emailId));
    }
}
