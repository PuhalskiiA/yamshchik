package ru.yamshchik.yamshchik.application.port.in;

/**
 * Уборка хранилища вложений.
 */
public interface CleanUpAttachmentsUseCase {

    /**
     * Удаляет содержимое вложений, если хранилище заполнено выше порога.
     *
     * @return сколько вложений удалено
     */
    int cleanUp();
}
