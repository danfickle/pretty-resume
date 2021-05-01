package com.openhtmltopdf.projects.resume;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

public class Entity {
  public static class Name {
    @JsonProperty public String first;
    @JsonProperty public String last;
  }
  
  public static class Birth {
    @JsonProperty public Integer year;
    @JsonProperty public String location;
  }
  
  public static class Experience {
    @JsonProperty public String company;
    @JsonProperty public String position;
    @JsonProperty public String timeperiod;
    @JsonProperty public String description;
  }
  
  public static class Education {
    @JsonProperty public String degree;
    @JsonProperty public String timeperiod;
    @JsonProperty public String description;
  }
  
  public static class Skill {
    @JsonProperty public String name;
    @JsonProperty public int level;
  }
  
  public static class Contact {
    @JsonProperty public String email;
    @JsonProperty public String phone;
    @JsonProperty public String street;
    @JsonProperty public String city;
    @JsonProperty public String website;
    @JsonProperty public String github;
  }
  
  public static class PersonEntity {
    @JsonProperty
    public ResumeEntity person;
    
    @JsonProperty
    public String lang;
  }
  
  public static class ResumeEntity {
    @JsonProperty
    public Name name;
    
    @JsonProperty
    public String position;
    
    @JsonProperty
    public Birth birth;
    
    @JsonProperty
    @JsonDeserialize(as=ArrayList.class, contentAs=Experience.class)
    public List<Experience> experience;
    
    @JsonProperty
    @JsonDeserialize(as=ArrayList.class, contentAs=Education.class)
    public List<Education> education;
    
    @JsonProperty
    @JsonDeserialize(as=ArrayList.class, contentAs=Skill.class)
    public List<Skill> skills;
    
    @JsonProperty
    public String skillDescription;
    
    @JsonProperty
    public Contact contact;
  }

  public static class Headings {
    @JsonProperty
    public String contact;
    
    @JsonProperty
    public String experience;
    
    @JsonProperty
    public String education;
    
    @JsonProperty
    public String skills;
  }
  
  public static class LangEntity {
    @JsonProperty
    public Headings headings;
  }
}
