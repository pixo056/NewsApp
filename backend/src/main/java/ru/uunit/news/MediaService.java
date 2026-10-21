package ru.uunit.news;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@Service
public class MediaService {

  private final Path directory;
  private final String ffmpeg;
  private final String ffprobe;
  private final JdbcTemplate db;
  private final ObjectMapper mapper;
  private final Accounts accounts;
  private final Semaphore videoSlots = new Semaphore(1);

  public MediaService(
    @Value("${app.local-directory}") String local,
    @Value("${app.ffmpeg}") String ffmpeg,
    @Value("${app.ffprobe}") String ffprobe,
    JdbcTemplate db,
    ObjectMapper mapper,
    Accounts accounts
  ) throws IOException {
    this.directory = Path.of(local).toAbsolutePath().normalize().resolve("media");
    Files.createDirectories(directory);
    this.ffmpeg = ffmpeg;
    this.ffprobe = ffprobe;
    this.db = db;
    this.mapper = mapper;
    this.accounts = accounts;
  }

  private Path safe(String filename) {
    Path path = directory.resolve(filename).normalize();
    if (!path.getParent().equals(directory)) throw ApiException.missing();
    return path;
  }

  @org.springframework.transaction.annotation.Transactional
  public void removeUnused(UUID id, Accounts.User user) throws IOException {
    Map<String, Object> asset = db
      .queryForList(
        "SELECT * FROM media_assets WHERE id=? AND owner_id=? FOR UPDATE",
        id,
        user.id()
      )
      .stream()
      .findFirst()
      .orElseThrow(ApiException::missing);
    if (
      db.queryForObject("SELECT count(*) FROM revision_media WHERE media_id=?", Integer.class, id) >
      0
    ) throw new ApiException(
      HttpStatus.CONFLICT,
      "Медиа связано с версией новости и хранится вместе с её историей"
    );
    db.update("DELETE FROM media_assets WHERE id=?", id);
    org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
      new org.springframework.transaction.support.TransactionSynchronization() {
        @Override
        public void afterCommit() {
          try {
            Files.deleteIfExists(safe((String) asset.get("filename")));
            if (asset.get("poster_filename") != null) Files.deleteIfExists(
              safe((String) asset.get("poster_filename"))
            );
          } catch (IOException ignored) {}
        }
      }
    );
  }

  public Map<String, Object> upload(MultipartFile file, Accounts.User user) throws IOException {
    accounts.verified(user);
    if (file.isEmpty() || file.getSize() > 100L * 1024 * 1024) throw ApiException.bad(
      "Размер файла: от 1 байта до 100 МиБ"
    );
    UUID id = UUID.randomUUID();
    Path input = safe(id + ".upload");
    String original = Optional.ofNullable(file.getOriginalFilename())
      .orElse("")
      .toLowerCase(Locale.ROOT);
    boolean isVideo = original.endsWith(".mp4") || original.endsWith(".mov");
    if (!isVideo && !original.matches(".*\\.(jpg|jpeg|png|webp)$")) throw ApiException.bad(
      "Выберите JPG, PNG, WebP, MP4 или MOV"
    );
    if (!isVideo && file.getSize() > 10L * 1024 * 1024) throw ApiException.bad(
      "Фотография должна быть не больше 10 МиБ"
    );
    file.transferTo(input);
    boolean acquired = false,
      success = false;
    Path output = safe(id + (isVideo ? ".mp4" : ".jpg"));
    Path poster = safe(id + "-poster.jpg");
    try {
      int width, height;
      Double duration = null;
      if (isVideo) {
        acquired = videoSlots.tryAcquire();
        if (!acquired) throw new ApiException(
          HttpStatus.TOO_MANY_REQUESTS,
          "Сейчас обрабатывается другое видео. Повторите загрузку немного позже."
        );
        JsonNode probe = mapper.readTree(
          run(
            List.of(
              ffprobe,
              "-v",
              "error",
              "-protocol_whitelist",
              "file,pipe",
              "-show_format",
              "-show_streams",
              "-of",
              "json",
              input.toString()
            ),
            30
          )
        );
        String format = probe.path("format").path("format_name").asText();
        if (!format.contains("mp4") && !format.contains("mov")) throw ApiException.bad(
          "Файл не является MP4/MOV"
        );
        JsonNode video = null;
        for (JsonNode stream : probe.path("streams"))
          if (
            stream.path("codec_type").asText().equals("video") &&
            stream.path("disposition").path("attached_pic").asInt() == 0
          ) {
            video = stream;
            break;
          }
        if (video == null) throw ApiException.bad("В файле отсутствует видеодорожка");
        width = video.path("width").asInt();
        height = video.path("height").asInt();
        duration = probe.path("format").path("duration").asDouble(-1);
        if (
          duration <= 0 ||
          duration > 60.25 ||
          Math.max(width, height) > 1920 ||
          Math.min(width, height) > 1080 ||
          width < 2 ||
          height < 2
        ) throw ApiException.bad(
          "Видео: до 60 секунд, не выше 1080p, включая вертикальную ориентацию"
        );
        run(
          List.of(
            ffmpeg,
            "-nostdin",
            "-v",
            "error",
            "-y",
            "-protocol_whitelist",
            "file,pipe",
            "-i",
            input.toString(),
            "-map",
            "0:v:0",
            "-map",
            "0:a:0?",
            "-t",
            "60",
            "-vf",
            "scale=trunc(iw/2)*2:trunc(ih/2)*2",
            "-c:v",
            "libx264",
            "-preset",
            "veryfast",
            "-crf",
            "25",
            "-pix_fmt",
            "yuv420p",
            "-c:a",
            "aac",
            "-b:a",
            "128k",
            "-movflags",
            "+faststart",
            "-threads",
            "2",
            output.toString()
          ),
          180
        );
        run(
          List.of(
            ffmpeg,
            "-nostdin",
            "-v",
            "error",
            "-y",
            "-i",
            output.toString(),
            "-frames:v",
            "1",
            poster.toString()
          ),
          30
        );
      } else {
        try (ImageInputStream stream = ImageIO.createImageInputStream(input.toFile())) {
          Iterator<ImageReader> readers = ImageIO.getImageReaders(stream);
          if (!readers.hasNext()) throw ApiException.bad(
            "Изображение повреждено или формат не поддерживается"
          );
          ImageReader reader = readers.next();
          try {
            reader.setInput(stream, true, true);
            width = reader.getWidth(0);
            height = reader.getHeight(0);
            if (width < 1 || height < 1 || (long) width * height > 24000000) throw ApiException.bad(
              "Фотография должна содержать не больше 24 мегапикселей"
            );
            BufferedImage source = reader.read(0);
            double scale = Math.min(1, 1920.0 / Math.max(width, height));
            width = Math.max(1, (int) (width * scale));
            height = Math.max(1, (int) (height * scale));
            BufferedImage target = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = target.createGraphics();
            g.setColor(java.awt.Color.WHITE);
            g.fillRect(0, 0, width, height);
            g.setRenderingHint(
              RenderingHints.KEY_INTERPOLATION,
              RenderingHints.VALUE_INTERPOLATION_BICUBIC
            );
            g.drawImage(source, 0, 0, width, height, null);
            g.dispose();
            ImageIO.write(target, "jpg", output.toFile());
          } finally {
            reader.dispose();
          }
        }
      }
      db.update(
        "INSERT INTO media_assets(id,owner_id,kind,filename,poster_filename,width,height,duration,size_bytes) VALUES(?,?,?,?,?,?,?,?,?)",
        id,
        user.id(),
        isVideo ? "video" : "image",
        output.getFileName().toString(),
        isVideo ? poster.getFileName().toString() : null,
        width,
        height,
        duration,
        Files.size(output)
      );
      success = true;
      Map<String, Object> result = new LinkedHashMap<>();
      result.put("id", id);
      result.put("kind", isVideo ? "video" : "image");
      result.put("url", "/api/media/" + id);
      if (isVideo) result.put("posterUrl", "/api/media/" + id + "/poster");
      result.put("width", width);
      result.put("height", height);
      result.put("duration", duration);
      return result;
    } catch (ApiException e) {
      throw e;
    } catch (Exception e) {
      throw ApiException.bad("Не удалось обработать медиа. Проверьте файл и попробуйте снова.");
    } finally {
      Files.deleteIfExists(input);
      if (acquired) videoSlots.release();
      if (!success) {
        Files.deleteIfExists(output);
        Files.deleteIfExists(poster);
      }
    }
  }

  private String run(List<String> args, int seconds) throws Exception {
    Path log = Files.createTempFile(directory, "process-", ".log");
    try {
      Process process = new ProcessBuilder(args)
        .redirectErrorStream(true)
        .redirectOutput(log.toFile())
        .start();
      if (!process.waitFor(seconds, TimeUnit.SECONDS)) {
        process.destroyForcibly();
        process.waitFor(5, TimeUnit.SECONDS);
        throw ApiException.bad(
          "Обработка заняла слишком много времени. Попробуйте более короткое видео."
        );
      }
      if (process.exitValue() != 0) throw ApiException.bad(
        "Некорректный или неподдерживаемый медиафайл"
      );
      return Files.readString(log);
    } finally {
      Files.deleteIfExists(log);
    }
  }

  public ResponseEntity<Resource> read(UUID id, boolean poster, Accounts.User user) {
    Map<String, Object> media = db
      .queryForList("SELECT * FROM media_assets WHERE id=?", id)
      .stream()
      .findFirst()
      .orElseThrow(ApiException::missing);
    boolean allowed = user != null && user.id().equals(media.get("owner_id"));
    if (!allowed) allowed =
      db.queryForObject(
        "SELECT count(*) FROM revision_media rm JOIN news n ON n.published_revision_id=rm.revision_id WHERE rm.media_id=? AND NOT n.deleted",
        Integer.class,
        id
      ) > 0;
    if (!allowed && user != null) allowed =
      accounts.admin(user) ||
      db.queryForObject(
        "SELECT count(*) FROM revision_media rm JOIN news_revisions r ON r.id=rm.revision_id JOIN news n ON n.id=r.news_id JOIN communities c ON c.id=n.community_id WHERE rm.media_id=? AND c.owner_id=? AND NOT c.official AND NOT n.deleted",
        Integer.class,
        id,
        user.id()
      ) > 0;
    if (!allowed) throw ApiException.missing();
    String filename = (String) media.get(poster ? "poster_filename" : "filename");
    if (filename == null) throw ApiException.missing();
    Path path = safe(filename);
    if (!Files.exists(path)) throw ApiException.missing();
    return ResponseEntity.ok()
      .cacheControl(CacheControl.noStore())
      .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
      .contentType(
        filename.endsWith(".mp4") ? MediaType.parseMediaType("video/mp4") : MediaType.IMAGE_JPEG
      )
      .body(new FileSystemResource(path));
  }
}
