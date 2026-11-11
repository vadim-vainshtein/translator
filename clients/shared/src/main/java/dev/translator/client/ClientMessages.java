package dev.translator.client;

import dev.translator.client.TranslationSession.SessionEnded;
import dev.translator.client.TranslationSession.LogoutFailure;
import dev.translator.client.TranslatorHttp.ApiFailure;
import dev.translator.client.TranslatorHttp.InvalidResponse;

import java.io.IOException;
import java.net.SocketTimeoutException;

public final class ClientMessages {
    private ClientMessages() {}

    public static String describe(Exception error) {
        if (error instanceof LogoutFailure failure) {
            String cleanup = failure.cleanupFailed ? " Не удалось удалить сохранённый вход." : "";
            String server = failure.revocationFailed
                    ? "Сервер не подтвердил завершение сессии."
                    : "Сессия завершена на сервере.";
            return server + cleanup;
        }
        if (error instanceof IllegalArgumentException) {
            return error.getMessage();
        }
        if (error instanceof SessionEnded) {
            return "Сессия завершена. Войдите снова.";
        }
        if (error instanceof ApiFailure failure) {
            return apiMessage(failure);
        }
        if (error instanceof InvalidResponse) {
            return "Сервер вернул неожиданный ответ.";
        }
        if (error instanceof SocketTimeoutException) {
            return "Время ожидания истекло. Результат запроса неизвестен.";
        }
        if (error instanceof IOException) {
            return "Не удалось обменяться данными с сервером. Проверьте адрес и подключение.";
        }
        return "Не удалось завершить операцию.";
    }

    private static String apiMessage(ApiFailure failure) {
        return switch (failure.code) {
            case "INVALID_CREDENTIALS" -> "Неверное имя пользователя или пароль.";
            case "INVALID_REQUEST" -> "Проверьте введённые данные.";
            case "UNSUPPORTED_LANGUAGE" -> "Язык не поддерживается. Проверьте код или название языка.";
            case "UNKNOWN_MODEL" -> "Модель недоступна. Обновите список моделей.";
            case "TEXT_TOO_LARGE", "REQUEST_TOO_LARGE" -> "Текст слишком длинный для этой модели.";
            case "TRANSLATION_REFUSED" -> "Модель отклонила запрос на перевод.";
            case "DAILY_QUOTA", "GLOBAL_DAILY_QUOTA" -> "Дневной лимит переводов исчерпан.";
            default -> statusMessage(failure.status);
        };
    }

    private static String statusMessage(int status) {
        return switch (status) {
            case 401 -> "Сервер отклонил вход. Проверьте имя пользователя и пароль.";
            case 403 -> "У аккаунта недостаточно прав для этой операции.";
            case 429 -> "Слишком много запросов. Подождите и попробуйте снова.";
            default -> "Запрос не выполнен (HTTP " + status + ").";
        };
    }
}
