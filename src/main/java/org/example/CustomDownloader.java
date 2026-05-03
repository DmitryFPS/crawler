package org.example;

import lombok.extern.slf4j.Slf4j;
import us.codecraft.webmagic.Page;
import us.codecraft.webmagic.Request;
import us.codecraft.webmagic.Task;
import us.codecraft.webmagic.downloader.HttpClientDownloader;

@Slf4j
public class CustomDownloader extends HttpClientDownloader {

    @Override
    public Page download(Request request, Task task) {
        // 👇 Здесь можно добавить свою логику ДО скачивания

        // Пример: логирование
        log.info("Downloading: {}", request.getUrl());

        // Пример: пропуск по условию
        if (request.getUrl().matches(".*\\.(pdf|jpg|png)$")) {
            Page page = Page.ofSuccess(request);
            page.setSkip(true);
            return page;
        }

        // 👇 Вызываем родительский метод для реального скачивания
        return super.download(request, task);
    }
}
