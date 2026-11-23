FROM postgres:16-alpine3.24@sha256:f2f98726820d0342055351c6e9d36e705f5bfffb3dd53ff556325114b08afb6d AS build
RUN apk add --no-cache build-base
ADD --checksum=sha256:cac0b10c360f05b2d521200105ba3697e773d4cd3731f5a915a7e37ebe0bea85 https://codeload.github.com/pgvector/pgvector/tar.gz/refs/tags/v0.8.7 /tmp/pgvector.tar.gz
WORKDIR /tmp/pgvector
RUN tar -xzf /tmp/pgvector.tar.gz --strip-components=1 \
    && make OPTFLAGS="" with_llvm=no \
    && make with_llvm=no DESTDIR=/install install

FROM postgres:16-alpine3.24@sha256:f2f98726820d0342055351c6e9d36e705f5bfffb3dd53ff556325114b08afb6d
COPY --from=build /install/usr/local/ /usr/local/
