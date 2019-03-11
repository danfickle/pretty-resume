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
import java.sql.Statement;
import java.util.Base64;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;

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
	private static final ObjectMapper MAPPER = new ObjectMapper();
	private static final TemplateEngine THYMELEAF = new TemplateEngine();
	private static final Map<String, String> langs = new ConcurrentHashMap<>();
	private static final Map<String, String> templates = new ConcurrentHashMap<>();
	private static final Map<Editor, String> editorPages = new EnumMap<>(Editor.class);
	
	private static void loadLang(Languages lang) {
		try {
			langs.put(lang.slug, IOUtils.toString(Generator.class.getResource("/languages/" + lang.slug + ".json"), StandardCharsets.UTF_8));
			LOGGER.info("Loaded language file: {}", lang);
		} catch (IOException e) {
			e.printStackTrace();
			System.exit(-1);
		}
	}

	private static void loadTemplate(Template t) {
		try {
			templates.put(t.slug, IOUtils.toString(Generator.class.getResource("/" + t.slug + ".xhtml"), StandardCharsets.UTF_8));
			LOGGER.info("Loaded template: {}", t);
		} catch (IOException e) {
			e.printStackTrace();
			System.exit(-1);
		}
	}
	
	private static void loadEditorPage(Editor e) {
		try {
			editorPages.put(e, IOUtils.toString(Generator.class.getResource("/pages/" + e.toString().toLowerCase(Locale.US) + ".xhtml"), StandardCharsets.UTF_8)); 
			LOGGER.info("Loaded editor file: {}", e);
		} catch (IOException ex) {
			ex.printStackTrace();
			System.exit(-1);
		}
	}
	
	static {
		MAPPER.enable(JsonParser.Feature.ALLOW_UNQUOTED_FIELD_NAMES);
		MAPPER.enable(JsonParser.Feature.ALLOW_SINGLE_QUOTES);
		MAPPER.enable(JsonParser.Feature.ALLOW_MISSING_VALUES);
		MAPPER.enable(JsonParser.Feature.ALLOW_COMMENTS);
		MAPPER.enable(JsonParser.Feature.ALLOW_TRAILING_COMMA);
		
		Stream.of(Editor.values()).forEach(Generator::loadEditorPage);
		Stream.of(Languages.values()).forEach(Generator::loadLang);
		Stream.of(Template.values()).forEach(Generator::loadTemplate);
	}
	
	public static void main(String[] args) throws Exception {
		Class.forName("org.h2.Driver");
		
		String defaultResume = IOUtils.toString(Generator.class.getResource("/default-resume.json"), "UTF-8");
		
		try(Connection conn = DriverManager.getConnection("jdbc:h2:mem:resume;DB_CLOSE_DELAY=-1", "sa", "")) {
			String sql = 
				"CREATE TABLE IF NOT EXISTS resumes ( " +
				"id BIGINT IDENTITY PRIMARY KEY, json VARCHAR(10000), token VARCHAR(50), ts TIMESTAMP, template VARCHAR(64) )";
			
			conn.createStatement().executeUpdate(sql);
		}
		
		post("/upload", (req, res) -> {
			try(Connection conn = DriverManager.getConnection("jdbc:h2:mem:resume;DB_CLOSE_DELAY=-1", "sa", "")) {
				String json = req.queryParams("resumejson");
				
				// Check we support the template.
				if (!Stream.of(Template.values()).anyMatch(t -> t.slug.equals(req.queryParams("templateslug")))) {
					LOGGER.warn("Tried to use non-existant template: {}", req.queryParams("templateslug"));
					halt(404);
				}
				
				// Validate the json by trying to map it.
				Entity.PersonEntity person = MAPPER.readValue(json, Entity.PersonEntity.class);
				
				// Check if we support the language.
				if (!Stream.of(Languages.values()).anyMatch(l -> l.slug.equals(person.lang))) {
					LOGGER.warn("Tried to use non-existant language: {}", person.lang);
					halt(404);
				}
				
				// Make a security token.
				byte[] tokenBytes = new byte[20];
				RANDOM.nextBytes(tokenBytes);
				String token = Base64.getEncoder().encodeToString(tokenBytes);
				
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
			}
		});
		
		get("/raw", (req, res) -> {
			String template = editorPages.get(Editor.RAW);
			Context ctx = new Context();
			ctx.setVariable("templates", Template.values());
			ctx.setVariable("defaultResume", defaultResume);
			return THYMELEAF.process(template, ctx);
		});
		
		get("/", (req, res) -> {
			String template = editorPages.get(Editor.ANGULARJS1X);
			Context ctx = new Context();
			ctx.setVariable("templates", Template.values());
			ctx.setVariable("languages", Languages.values());
			ctx.setVariable("defaultResume", defaultResume);
			return THYMELEAF.process(template, ctx);
		});
		
		get("/resume", (req, res) -> {
			long id = Long.parseLong(req.queryParams("id"));
			String token = req.queryParams("token");
			
			try(Connection conn = DriverManager.getConnection("jdbc:h2:mem:resume;DB_CLOSE_DELAY=-1", "sa", "")) {
				// Delete all records older than 10 minutes for privacy and so the db doesn't get too large.
				conn.createStatement().executeUpdate("DELETE FROM resumes WHERE DATEDIFF('MINUTE', ts, NOW()) > 10");
				conn.commit();
				
				String sql = "SELECT token, json, template FROM resumes WHERE id = ?";
				
				PreparedStatement stmt = conn.prepareStatement(sql);
				stmt.setLong(1, id);
				ResultSet rs = stmt.executeQuery();
				rs.next();

				String tokenCompare = rs.getString(1);
				if (!MessageDigest.isEqual(token.getBytes("UTF-8"), tokenCompare.getBytes("UTF-8"))) {
					LOGGER.warn("Tried to access resume with incorrect token. Resume ID: {}", id);
					halt(403);
				}
				
				String json = rs.getString(2);
				Entity.PersonEntity person = MAPPER.readValue(json, Entity.PersonEntity.class);
				Entity.LangEntity lang = MAPPER.readValue(langs.get(person.lang), Entity.LangEntity.class);
				String templateSlug = rs.getString(3);
				String template = templates.get(templateSlug);
				
				LOGGER.info("Outputting resume PDF now. ID: {}, LANG: {}, TEMPLATE: {}", id, person.lang, templateSlug);
				
				Context ctx = new Context();
				ctx.setVariable("person", person.person);
				ctx.setVariable("lang", lang);
				String html = THYMELEAF.process(template, ctx);
								
				ByteArrayOutputStream os = new ByteArrayOutputStream();
				PdfRendererBuilder builder = new PdfRendererBuilder();
				builder.withHtmlContent(html, Generator.class.getResource("/root.htm").toExternalForm());
				builder.useFastMode();
				builder.toStream(os);
				builder.run();

				res.header("Content-Type", "application/pdf");
				return os.toByteArray();
			}
		});
    }
}
