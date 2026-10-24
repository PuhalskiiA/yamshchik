# Заглушка почтового сервера получателя с управляемым пределом темпа — для опытов с регулятором отправки
FROM eclipse-temurin:25-jdk AS build
WORKDIR /build
COPY SmtpStub.java .
RUN javac -d classes SmtpStub.java

FROM eclipse-temurin:25-jre
RUN useradd --system --no-create-home stub
WORKDIR /app
COPY --from=build /build/classes .
USER stub
EXPOSE 2525 8080
ENTRYPOINT ["java", "-cp", "/app", "SmtpStub"]
