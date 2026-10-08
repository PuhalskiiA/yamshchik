package ru.yamshchik.yamshchik.domain;

import java.io.IOException;
import java.io.InputStream;


/**
 * Источник содержимого вложения. Каждый вызов открывает новый поток с начала.
 */
@FunctionalInterface
public interface AttachmentContent {

    InputStream open() throws IOException;
}
