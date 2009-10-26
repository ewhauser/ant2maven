import groovy.text.SimpleTemplateEngine
import java.security.MessageDigest
import org.apache.commons.cli.OptionBuilder
import org.apache.commons.cli.Options
import org.apache.commons.cli.PosixParser
import org.apache.commons.io.FileUtils
import org.apache.ivy.core.settings.IvySettings
import org.apache.ivy.plugins.parser.m2.PomModuleDescriptorParser
import org.apache.tools.ant.Project
import org.apache.tools.ant.ProjectHelper
import org.apache.tools.ant.types.Path
import org.apache.commons.cli.HelpFormatter

//First grab the grapes we need for the script and create a few beans to hold some values
@Grab(group = 'org.apache.ant', module = 'ant', version = '1.7.1')
@Grab(group = 'commons-io', module = 'commons-io', version = '1.4')
@Grab(group = 'commons-cli', module = 'commons-cli', version = '1.2')
@Grab(group = 'org.apache.ivy', module = 'ivy', version = '2.1.0')
class ArtifactInfo {
  String groupId
  String artifactId
  String version
  String repoId
  String pomLink
  String scope

  def String toString() { "groupId: $groupId artifactId: $artifactId version: $version repoId: $repoId pomLink: $pomLink scope: $scope"}

  boolean equals(o) {
    if (this.is(o)) return true

    if (!o || getClass() != o.class) return false

    ArtifactInfo that = (ArtifactInfo)o

    if (artifactId ? !artifactId.equals(that.artifactId) : that.artifactId != null) return false
    if (groupId ? !groupId.equals(that.groupId) : that.groupId != null) return false
    if (version ? !version.equals(that.version) : that.version != null) return false

    return true
  }

  int hashCode() {
    int result

    result = (groupId ? groupId.hashCode() : 0)
    result = 31 * result + (artifactId ? artifactId.hashCode() : 0)
    result = 31 * result + (version ? version.hashCode() : 0)
    return result
  }
}

class Repo {
  String name
  String uri
  boolean legacy
}

class PathInfo implements Comparable {
  File file
  String scope

  boolean equals(o) {
    if (this.is(o)) return true
    if (!o || getClass() != o.class) return false
    PathInfo pathInfo = (PathInfo) o
    if (file ? !file.name?.equals(pathInfo.file?.name) : pathInfo.file?.name != null) return false
    return true
  }

  int hashCode() {
    return (file ? file?.name?.hashCode() : 0);
  }

  int compareTo(t) {
    return file.name.compareTo(((PathInfo)t).file.name)
  }
}

//Define the command line options
def options = new Options()
options.addOption(OptionBuilder.withArgName("a")
  .withLongOpt("antfile")
  .withDescription("The ant build file, defaults to build.xml")
  .hasArgs(1)
  .isRequired(false)
  .create("a"))
options.addOption(OptionBuilder.withArgName("p")
  .withLongOpt("paths")
  .withDescription("The ant paths to parse")
  .hasArgs(1)
  .isRequired(true)
  .create("p"))
options.addOption(OptionBuilder.withArgName("t")
  .withLongOpt("testpaths")
  .withDescription("The ant paths to parse for the test scope")
  .hasArgs(1)
  .create("t"))
options.addOption(OptionBuilder.withArgName("n")
  .withLongOpt("name")
  .withDescription("The name of the new project, defaults to current directory")
  .hasArgs(1)                                
  .isRequired(false)
  .create("n"))
options.addOption(OptionBuilder.withArgName("aid")
  .withLongOpt("artifactId")
  .withDescription("The artifactId of the new project, defaults to current directory")
  .hasArgs(1)
  .isRequired(false)
  .create("aid"))
options.addOption(OptionBuilder.withArgName("gid")
  .withLongOpt("groupId")
  .withDescription("The groupId of the new project")
  .hasArgs(1)
  .isRequired(true)
  .create("gid"))
options.addOption(OptionBuilder.withArgName("v")
  .withLongOpt("version")
  .withDescription("The version of the new project, defaults to 1.0-SNAPSHOT")
  .hasArgs(1)                                               
  .isRequired(false)
  .create("v"))
options.addOption(OptionBuilder.withArgName("g")
  .withLongOpt("groovy")
  .withDescription("Enable groovy support")
  .isRequired(false)
  .create("g"))
options.addOption(OptionBuilder.withArgName("o")
  .withLongOpt("output")
  .withDescription("Name of the POM file, defaults to pom.xml")
  .hasArgs(1)
  .isRequired(false)
  .create("o"))
options.addOption(OptionBuilder.withArgName("u")
  .withLongOpt("nexusurl")
  .withDescription("Base URL for Nexus repository to search against")
  .hasArgs(1)
  .isRequired(false)
  .create("u"))
options.addOption(OptionBuilder.withArgName("?")
  .withLongOpt("help")
  .withDescription("Prints help")
  .isRequired(false)
  .create("?"))

//create the help format
def help = {
  def formatter = new HelpFormatter();
  formatter.printHelp("ant2maven", options);
  System.exit(1)
}

def cmd = null
try {
  def parser = new PosixParser();
  cmd = parser.parse(options, args);
} catch (Exception e) { e.printStackTrace(); help() }

if (cmd.getOptionValue("?")) help()

//Load the ant file
def project = new Project();
def buildFile = new File(cmd.getOptionValue("a", "build.xml"))
project.init();
ProjectHelper.configureProject(project, buildFile);

//Function to hash the dependency
def hash = { file ->
  def md = MessageDigest.getInstance("SHA1")
  md.reset()

  def data = FileUtils.readFileToByteArray(file)
  md.update(data)

  def digest = md.digest()
  def hash = new StringBuilder()
  for (int i = 0; i < digest.length; i++) {
    def hex = Integer.toHexString(digest[i]);
    if (hex.length() == 1) hex = "0" + hex
    hex = hex.substring(hex.length() - 2)
    hash << hex;
  }

  hash.toString()
}

//Get the URL for the nexus repository and clean it up
def baseNexusUrl = cmd.getOptionValue("u", "http://repository.sonatype.org").replaceAll("(?<!:)/(//*)", "/")
if (!baseNexusUrl.endsWith('/')) baseNexusUrl = baseNexusUrl + "/"

//Retrieve all the repositories from the Nexus server.  Skip over Maven Central, we don't want to add it to the pom
def repoXml = "${baseNexusUrl}service/local/repositories".toURL().text
def rootElement= new XmlParser().parseText(repoXml)
def repos = []
rootElement.data.'repositories-item'.each {
  if ("Central".equals(it.name.text())) return
  repos << new Repo(name: it.name.text(), uri: it.resourceURI.text(), legacy: "maven1".equals(it.format.text()))  
}

//Load all of the build paths defined in the ant file and indicate their scope
def buildPaths = { p, scope -> new PathInfo(file: new File(p), scope: scope) }
def paths = []
cmd.getOptionValues("p").each { ((Path)project.getReference(it)).list().each { paths << buildPaths(it, "compile") }}
cmd.getOptionValues("t").each { ((Path)project.getReference(it)).list().each { paths << buildPaths(it, "test") }}

//Search the nexus repository for the JAR using the hash of the JAR file
def leftovers = []
def artifacts = []
new TreeSet(paths).each { p ->
  def hashValue = hash(p.file)

  print "Searching for artifact for ${p.file.name}..."
  def xml = "${baseNexusUrl}service/local/data_index?sha1=${hashValue}".toURL().text

  def root = new XmlParser().parseText(xml)
  if (!root.data.artifact) {
    leftovers << p.file.name
    println "No results"
    return
  }
  root.data.artifact.each {
    //Try to find a pom if no pom link is supplied
    def pomLink
    if (it.pomLink?.text()) {
      pomLink = it.pomLink.text()
    } else {
      def resourceUri = it.resourceURI.text()
      pomLink = resourceUri.substring(0, resourceUri.lastIndexOf('.')) + ".pom"
    }

    def ai = new ArtifactInfo(groupId: it.groupId.text(), artifactId: it.artifactId.text(), version: it.version.text(),
			repoId: it.repoId.text(), pomLink: pomLink, scope: p.scope)
    artifacts << ai
    println "Found $ai.groupId $ai.artifactId $ai.version"
  }
}

//Attempt to find the transitive dependencies for each JAR.  This uses the Apache Ivy APIs so that we parse any placeholders in the pom
def deps = []
artifacts.each { a ->
  try {
    println "artifact -> $a"
    def pom = PomModuleDescriptorParser.getInstance().parseDescriptor(new IvySettings(), a.pomLink.toURL(), false)
    pom.dependencies.each {
      def rev = it.dependencyRevisionId
      if (it.transitive && !Arrays.asList(it.moduleConfigurations).contains("test")) {
        deps << new ArtifactInfo(artifactId: rev.name, groupId: rev.organisation, version: rev.revision)
      }
    }
  } catch (Exception e) { println "Could not retrieve POM for $a ... Skipping because: ${e.message}" }
}

def currentDir = System.getProperty("user.dir")
def defaultName = currentDir.substring(currentDir.lastIndexOf(System.getProperty("file.separator")))

//Try to give the user an indicator that their leftover is probably a transitive dependency
def cleaner = { it.replaceAll(/^(.*)(\d+\.\d+.*)$/, "") }
def transitiveLeftovers = []
leftovers.each { l ->
  def cl = l.replaceAll(/(\w|-)((\d+\.\d+)+).*$/, "\$1").replaceAll('-', '')
  if (cl.endsWith('.jar')) cl = cl.substring(0, cl.size() - 4)
  println "$l -> $cl"
  if (deps.find { cl.equals(it.artifactId.replaceAll('-', '')) }) transitiveLeftovers << l
}

//Render the pom.xml
def binding = [
        name: cmd.getOptionValue("n", defaultName),
        artifactId: cmd.getOptionValue("aid", defaultName),
        groupId: cmd.getOptionValue("gid"),
        version: cmd.getOptionValue("v", "1.0-SNAPSHOT"),
        groovy: Boolean.valueOf(cmd.getOptionValue("g", "false")),
        repositories: repos,
        leftovers: leftovers.sort(),
        transitiveLeftovers: transitiveLeftovers,
        dependencies: artifacts - deps] //Don't include transitive dependencies
def pomTemplate = """
<?xml version="1.0" encoding="UTF-8"?>

<project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/maven-v4_0_0.xsd">

    <modelVersion>4.0.0</modelVersion>

    <groupId><% out << groupId %></groupId>
    <artifactId><% out << artifactId %></artifactId>
    <name><% out << name %></name>
    <version><% out << version %></version>

    <repositories>
      <% repositories.each { r -> %>
      <repository>
        <id><% out << r.name %></id>
        <url><% out << r.uri %></url>
        <layout><% r.legacy ? "legacy" : "m2" %></layout>
      </repository>
      <% } %>
    </repositories>

    <dependencies>
        <% if (groovy) { %>
        <dependency>
            <groupId>org.codehaus.groovy.maven.runtime</groupId>
            <artifactId>gmaven-runtime-1.6</artifactId>
            <version>1.0</version>
        </dependency><% } %>
        <% dependencies.each { d -> %>
        <dependency>
            <groupId><% out << d.groupId %></groupId>
            <artifactId><% out << d.artifactId %></artifactId>
            <% if (d.version) { out << "<version>" + d.version + "</version>" } %>
            <% if (d.scope == "test") { out << "<scope>test</scope>" } %>
        </dependency>
        <% } %>
        <% leftovers.each { l -> %>
        <!--
        <% if (transitiveLeftovers.contains(l)) { out << "This JAR is likely a transitive dependency." } %>
        <dependency>
            <groupId><% out << l %></groupId>
            <artifactId><% out << l %></artifactId>
        </dependency>
        -->
        <% } %>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <artifactId>maven-compiler-plugin</artifactId>
                <version>2.0.2</version>
                <configuration>
                    <source>1.6</source>
                    <target>1.6</target>
                </configuration>
            </plugin>
            <% if (groovy) { %>
            <plugin>
                <groupId>org.codehaus.groovy.maven</groupId>
                <artifactId>gmaven-plugin</artifactId>
                <version>1.0</version>
                <executions>
                    <execution>
                        <goals>
                            <goal>generateStubs</goal>
                            <goal>compile</goal>
                            <goal>generateTestStubs</goal>
                            <goal>testCompile</goal>
                        </goals>
                    </execution>
                </executions>
            </plugin>
            <% } %>
            <plugin>
                <artifactId>maven-eclipse-plugin</artifactId>
                <version>2.5.1</version>
                <configuration>
                    <additionalProjectnatures>
                        <projectnature>org.springframework.ide.eclipse.core.springnature</projectnature>
                    </additionalProjectnatures>
                    <additionalBuildcommands>
                        <buildcommand>org.springframework.ide.eclipse.core.springbuilder</buildcommand>
                    </additionalBuildcommands>
                    <downloadSources>true</downloadSources>
                    <downloadJavadocs>true</downloadJavadocs>
                    <wtpversion>1.5</wtpversion>
                </configuration>
            </plugin>
            <plugin>
                <artifactId>maven-idea-plugin</artifactId>
                <version>2.1</version>
                <configuration>
                    <downloadSources>true</downloadSources>
                    <downloadJavadocs>true</downloadJavadocs>
                    <dependenciesAsLibraries>true</dependenciesAsLibraries>
                    <useFullNames>false</useFullNames>
                </configuration>
            </plugin>
        </plugins>
    </build>

    <reporting>
      <plugins>
        <plugin>
          <groupId>org.codehaus.mojo</groupId>
          <artifactId>cobertura-maven-plugin</artifactId>
        </plugin>
        <plugin>
          <groupId>org.codehaus.mojo</groupId>
          <artifactId>findbugs-maven-plugin</artifactId>
          <version>2.0.1</version>
        </plugin>
      </plugins>
    </reporting>

</project>
"""

def engine = new SimpleTemplateEngine()
FileUtils.writeStringToFile new File(cmd.getOptionValue("o", "pom.xml")), engine.createTemplate(pomTemplate).make(binding).toString()
