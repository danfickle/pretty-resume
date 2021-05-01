package com.openhtmltopdf.projects.resume;

import static spark.Spark.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.stream.Stream;

import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import spark.Request;
import spark.Response;
import spark.Route;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonFactoryBuilder;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import com.openhtmltopdf.util.XRLog;

public class Generator {
  
  private static enum Editor {
    RAW,
    ANGULARJS1X;
  }
  
  private static enum Languages {
    ENGLISH("en"),
    GERMAN("de"),
    FRENCH("fr");
    
    public final String slug;
    
    private Languages(String slug) {
      this.slug = slug;
    }
  }
  
  private static enum Template {
    MATERIAL_BLUE("material-blue");
    
    public final String slug;
    
    private Template(String slug) {
      this.slug = slug;
    }
  }

  private static final Logger LOGGER = LoggerFactory.getLogger(Generator.class);
  private static final SecureRandom RANDOM = new SecureRandom();
  private static final ObjectMapper MAPPER = new ObjectMapper(createJsonFactory());
  private static final TemplateEngine THYMELEAF = new TemplateEngine();
  private static final Map<String, String> langs = loadLanguages();
  private static final Map<String, String> templates = loadTemplates();
  private static final Map<Editor, String> editorPages = loadEditorPages();
  private static final String defaultResume = loadResourceString("/", "default-resume", ".json");
  
  private static String loadResourceString(String resPath, String resFile, String resExt) {
    try {
      String ret = IOUtils.toString(Generator.class.getResource(resPath + resFile + resExt), StandardCharsets.UTF_8);
      LOGGER.info("Loaded resource file '{}' with {} chars", resFile, ret.length());
      return ret;
    } catch (IOException e) {
      LOGGER.error("FATAL - Unable to load resource file '{}'", resFile, e);
      System.exit(-1);
      return null;
    }
  }

  private static Map<String, String> loadResourceStrings(
    Stream<String> keys,
    String resPath,
    String resExt) {
      Map<String, String> map = new ConcurrentHashMap<>();
      keys.forEach(key -> map.put(key, loadResourceString(resPath, key, resExt)));
      return map;
  }

  private static Map<String, String> loadLanguages() {
    LOGGER.info("Loading language files now...");
    return loadResourceStrings(
      Stream.of(Languages.values()).map(lang -> lang.slug),
      "/languages/", ".json");
  }

  private static Map<String, String> loadTemplates() {
    LOGGER.info("Loading templates now...");
    return loadResourceStrings(
      Stream.of(Template.values()).map(t -> t.slug),
      "/", ".xhtml");
  }

  private static Map<Editor, String> loadEditorPages() {
    LOGGER.info("Loading editor pages now...");

    // NOTE: Could use an EnumMap here but not sure about thread-safety.
    Map<Editor, String> map = new ConcurrentHashMap<>();
    Stream.of(Editor.values()).forEach(ed -> map.put(ed, loadEditorPage(ed)));
    return map;
  }
  
  private static String loadEditorPage(Editor e) {
    String resFile = e.toString().toLowerCase(Locale.US);
    return loadResourceString("/pages/", resFile, ".xhtml");
  }
  
  /**
   * @return A JsonFactory that will accept any old crap!
   */
  private static JsonFactory createJsonFactory() {
    JsonFactoryBuilder builder = new JsonFactoryBuilder();
  
    builder.enable(JsonReadFeature.ALLOW_MISSING_VALUES);
    builder.enable(JsonReadFeature.ALLOW_TRAILING_COMMA);
    builder.enable(JsonReadFeature.ALLOW_UNQUOTED_FIELD_NAMES);
    builder.enable(JsonReadFeature.ALLOW_SINGLE_QUOTES);
    builder.enable(JsonReadFeature.ALLOW_JAVA_COMMENTS);

    return builder.build();
  }

  private static final String HEXES = "0123456789ABCDEF";
  private static String getHex( byte [] raw ) {
      final StringBuilder hex = new StringBuilder( 2 * raw.length );
      for ( final byte b : raw ) {
          hex.append(HEXES.charAt((b & 0xF0) >> 4))
              .append(HEXES.charAt((b & 0x0F)));
      }
      return hex.toString();
  }

  private static Connection getDb() throws SQLException {
    return DriverManager.getConnection("jdbc:h2:mem:resume;DB_CLOSE_DELAY=-1", "sa", "");
  }

  private static Object uploadResumeJson(Request req, Response res) {
    try(Connection conn = getDb()) {
      String json = req.queryParams("resumejson");
      String requestedTemplate = req.queryParams("templateslug");
      
      // Check we support the template.
      if (!templates.containsKey(requestedTemplate)) {
        LOGGER.warn("Tried to use non-existant template: {}", req.queryParams("templateslug"));
        halt(404);
      }
      
      // Validate the json by trying to map it.
      Entity.PersonEntity person;
      try {
        person = MAPPER.readValue(json, Entity.PersonEntity.class);
      } catch (JsonProcessingException e) {
        LOGGER.warn("Tried to upload invalid resume json", e);
        halt(400);
        return null;
      }
      
      // Check if we support the language.
      if (!langs.containsKey(person.lang)) {
        LOGGER.warn("Tried to use non-existant language: {}", person.lang);
        halt(404);
      }
      
      // Make a security token.
      byte[] tokenBytes = new byte[20];
      RANDOM.nextBytes(tokenBytes);
      String token = getHex(tokenBytes);
      
      // Insert into db.
      String sql = "INSERT INTO resumes(id, json, token, ts, template) VALUES(NULL, ?, ?, NOW(), ?)";
      PreparedStatement stmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
      stmt.setString(1, json);
      stmt.setString(2, token);
      stmt.setString(3, req.queryParams("templateslug"));
      stmt.executeUpdate();
      
      // Get the db generated id.
      ResultSet generated = stmt.getGeneratedKeys();
      generated.next();
      long id = generated.getLong(1);
      
      conn.commit();
      
      // Redirect to the actual generator.
      res.redirect("/resume?id=" + id + "&token=" + token);
      return null;
    } catch (SQLException sqlEx) {
      LOGGER.warn("Couldn't use db", sqlEx);
      halt(500);
      return null;
    }
  }
  
  private static Object createResume(Request req, Response res) {
    long id = Long.parseLong(req.queryParams("id"));
    String token = req.queryParams("token");
    
    try(Connection conn = getDb()) {
      // Delete all records older than 10 minutes for privacy and so the db doesn't get too large.
      conn.createStatement().executeUpdate("DELETE FROM resumes WHERE DATEDIFF('MINUTE', ts, NOW()) > 10");
      conn.commit();
      
      String sql = "SELECT token, json, template FROM resumes WHERE id = ?";
      
      PreparedStatement stmt = conn.prepareStatement(sql);
      stmt.setLong(1, id);
      ResultSet rs = stmt.executeQuery();

      if (!rs.next()) {
        LOGGER.warn("Tried to retrieve resume with id {} but does not exist!", id);
        halt(404);
      }

      String tokenCompare = rs.getString(1);
      if (!MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8), tokenCompare.getBytes(StandardCharsets.UTF_8))) {
        LOGGER.warn("Tried to access resume with incorrect token. Resume ID: {}, IP: {}", id, req.ip());
        halt(403);
      }
      
      String json = rs.getString(2);
      Entity.PersonEntity person;
      Entity.LangEntity lang;
      try {
        person = MAPPER.readValue(json, Entity.PersonEntity.class);
        lang = MAPPER.readValue(langs.get(person.lang), Entity.LangEntity.class);
      } catch (JsonProcessingException e) {
        LOGGER.error("Unable to read previously validated json!", e);
        halt(500);
        return null;
      }

      String templateSlug = rs.getString(3);
      String template = templates.get(templateSlug);
      
      LOGGER.info("Outputting resume PDF now. ID: {}, LANG: {}, TEMPLATE: {}", id, person.lang, templateSlug);
      
      Context ctx = new Context();
      ctx.setVariable("person", person.person);
      ctx.setVariable("lang", lang);
      String html = THYMELEAF.process(template, ctx);
              
      try (ByteArrayOutputStream os = new ByteArrayOutputStream()) {
          PdfRendererBuilder builder = new PdfRendererBuilder();
          builder.withHtmlContent(html, Generator.class.getResource("/root.htm").toExternalForm());
          builder.useFastMode();
          builder.toStream(os);
          builder.run();
      
          res.header("Content-Type", "application/pdf");
          return os.toByteArray();
      } catch (IOException ex) {
        LOGGER.error("Unable to generate PDF!", ex);
        halt(500);
        return null;
      }

    } catch (SQLException sqlEx) {
      LOGGER.warn("Couldn't use db", sqlEx);
      halt(500);
      return null;
    }
  }

  private static Route showPage(Editor page) {
    return (req, res) -> {
      String template = editorPages.get(page);
      Context ctx = new Context();
      ctx.setVariable("templates", Template.values());
      ctx.setVariable("languages", Languages.values());
      ctx.setVariable("defaultResume", defaultResume);
      return THYMELEAF.process(template, ctx);
    };
  }

  public static void main(String[] args) throws ClassNotFoundException {
    Class.forName("org.h2.Driver");

    // Disable the chatty logging.
    XRLog.listRegisteredLoggers().forEach(logger -> XRLog.setLevel(logger, Level.WARNING));

    try(Connection conn = getDb()) {
      String sql = 
        "CREATE TABLE IF NOT EXISTS resumes ( " +
        "id BIGINT IDENTITY PRIMARY KEY, json VARCHAR(10000), token VARCHAR(50), ts TIMESTAMP, template VARCHAR(64) )";
      
      conn.createStatement().executeUpdate(sql);
    } catch (SQLException sqlEx) {
      LOGGER.error("FATAL - Unable to use db on startup", sqlEx);
      System.exit(-1);
    }
    
    post("/upload", Generator::uploadResumeJson);

    get("/raw", showPage(Editor.RAW));
    get("/", showPage(Editor.ANGULARJS1X));
    get("/resume", Generator::createResume);
  }
}
