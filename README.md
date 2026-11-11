# Система «Лікарня» (REST-сервіс)

Лабораторна №3, варіант 1. Дані зберігаються в пам'яті, база даних не потрібна.

## Збірка і запуск

Потрібні Java 17 або новіша та Maven 3.9+.

```bash
mvn -q package
java -jar target/hospital.jar
```

Сервіс слухає порт **8080**. Роль користувача передається в заголовку `X-Role` (`DOCTOR` або `NURSE`).

## Ендпоінти

- `POST /patients`, `GET /patients`, `GET|PUT|DELETE /patients/{id}`
- `POST /patients/{id}/diagnosis` (лікар)
- `POST /patients/{id}/prescriptions`, `GET /patients/{id}/prescriptions?status=done|pending`
- `POST /prescriptions/{id}/execute`, `DELETE /prescriptions/{id}`
- `POST /patients/{id}/discharge` (лікар, потрібен `finalDiagnosis`)
