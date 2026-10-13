ARG PYTHON_IMAGE=python:3.12-alpine
FROM ${PYTHON_IMAGE}
WORKDIR /app
COPY notification.py .
USER 10001:10001
EXPOSE 9080
CMD ["python", "notification.py"]
