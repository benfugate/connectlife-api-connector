FROM php:8.3-cli-alpine

# hadolint ignore=DL3018
RUN apk add --no-cache libzip && \
    apk add --no-cache --virtual .build-deps libzip-dev && \
    docker-php-ext-install pcntl zip && \
    apk del .build-deps && \
    mkdir /home/app

COPY --from=composer:2 /usr/bin/composer /usr/bin/composer
COPY ./ /home/app
WORKDIR /home/app
RUN composer install --no-interaction --no-dev
RUN chmod a+x run.sh

CMD [ "/bin/ash", "-c", "php artisan app:mqtt-loop" ]
