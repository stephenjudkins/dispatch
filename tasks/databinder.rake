repositories.remote << "http://repository.jboss.com/maven2/"
repositories.remote << "http://download.java.net/maven/2/"
repositories.remote << "http://databinder.net/snapshot/"
repositories.remote << "http://scala-tools.org/repo-releases"

repositories.local = ENV['M2_REPO'] if ENV['M2_REPO']

def child_artifact(child_spec, parent_artifact, path)
  parent_artifact.invoke
  artifact(child_spec) do |task|
    file_name = File.basename(path) 
    dest_path = File.dirname(task.name) 
    unz = Unzip.new(dest_path => parent_artifact)
    unz.from_path(File.dirname(path)).include(file_name)
    unz.extract()
    tgt = (File.join(dest_path, file_name))
    mv(tgt, task.name) if not File.exist? task.name
  end
end

def dep_preview(path, parent_spec)
  file_name = File.basename(path)
  parent_artifact = artifact(parent_spec)
  file("dep_preview/" + file_name => parent_artifact) do |task|
    dest_path = File.dirname(task.name) 
    unz = Unzip.new(dest_path => parent_artifact)
    unz.from_path(File.dirname(path)).include(file_name)
    unz.extract()
  end
end

module SoftFail
  def fail_download(remote_uris)
    puts "Can't find javadoc #{to_spec}\n"
  end
end

LIB_DOCS='lib_docs'
def doc_tasks(jar_spec)
  artifacts(jar_spec).map do |a|
    doc_a = artifact(a.to_hash.merge({:classifier=>'javadoc'}))
    doc_a.extend SoftFail
    file(_(LIB_DOCS) + '/' + a.to_spec => doc_a) do |task|
      unzip(task.name => doc_a) if File.exist? doc_a.name
    end
  end
end

def embed_server

  compile.with JETTY
  scalac.with JETTY if defined? scalac
  test.with JDK_LOG

  def scala_libs()
    FileList["#{ENV["SCALA_HOME"]}/lib/*"]
  end

  def java_runner(cp, params = [], main_class = 'net.databinder.web.DataServer')
    params << "-Dmail.smtp.host=$SMTP_HOST" if ENV["SMTP_HOST"]
    params << '-Djetty.port=' + ENV['JETTY_PORT'] if ENV['JETTY_PORT']
    params << '-Djetty.ajp.port=' + ENV['JETTY_AJP_PORT'] if ENV['JETTY_AJP_PORT']
    params << '-Djetty.contextPath=' + ENV['JETTY_CONTEXT'] if ENV['JETTY_CONTEXT']
    # set utf-8 default charset
    params << '-Dfile.encoding=UTF-8'

    mkpath _('target/tmp'), :verbose=>false
    params << '-Djava.io.tmpdir=' + _('target/tmp')

    cp += [compile.target.to_s]
    if defined? scalac then
      cp += scalac.classpath + scala_libs
    end
    cp

    ENV['JAVA_HOME'] + "/bin/java $JAVA_OPTIONS " << params.join(" ") << ' -cp ' << cp.uniq.join(":") << ' ' << main_class
  end

  def rebel_params()
    if ENV["JAVA_REBEL"]
    	["-noverify", "-javaagent:$JAVA_REBEL"]
   	else [] end
  end
  
  task :run => :build do
    system java_runner(test.classpath, rebel_params)
  end

  task :play => :build do
    raise('sorry, a SCALA_HOME is required to play') if not ENV['SCALA_HOME']
    system java_runner(test.classpath + scala_libs, rebel_params, 'scala.tools.nsc.MainGenericRunner')
  end

  def pid_f() _("server.pid") end

  def pid() File.exist?(pid_f) && IO.read(pid_f).to_i end

  task :start => :package do
    cp = compile.classpath + artifacts(LOG4J).map { |a| a.name }
    system 'nohup ' << java_runner(cp, ['-server']) << '>/dev/null &\echo $! > ' << pid_f
    puts "started server pid: " << pid().to_s
  end

  task :stop do
    if pid
      begin
        Process.kill("TERM", pid)
      rescue Errno::ESRCH
        puts "server not running at pid: #{pid}; removing record"
      end
      rm pid_f
    else
      puts "no server id on record"
    end
  end
end

WICKET_VERS='1.3.2'
WICKET_SELF = group("wicket", "wicket-auth-roles", "wicket-extensions", :under=>"org.apache.wicket", :version=>WICKET_VERS)
WICKET_DATETIME=["org.apache.wicket:wicket-datetime:jar:#{WICKET_VERS}", "joda-time:joda-time:jar:1.4"]
WICKET=[WICKET_SELF, "commons-collections:commons-collections:jar:3.2","org.slf4j:slf4j-api:jar:1.4.2"]
MAIL = ["javax.mail:mail:jar:1.4", "javax.activation:activation:jar:1.1"]
LOG4J = ["org.slf4j:slf4j-log4j12:jar:1.4.2","log4j:log4j:jar:1.2.14", MAIL]
JDK_LOG = ["org.slf4j:slf4j-jdk14:jar:1.4.2"]

BATIK = ["org.apache.xmlgraphics:batik-gvt:jar:1.7", "org.apache.xmlgraphics:batik-awt-util:jar:1.7"]
COMMONS_LOG="commons-logging:commons-logging:jar:1.1.1"
HTTPCLIENT = ["org.apache.httpcomponents:httpclient:jar:4.0-alpha3", \
  "org.apache.httpcomponents:httpcore:jar:4.0-beta1", "commons-codec:commons-codec:jar:1.3", COMMONS_LOG]

HIBERNATE_CORE = "org.hibernate:hibernate:jar:3.2.6.ga"
HIBERNATE_SELF = [HIBERNATE_CORE,"org.hibernate:hibernate-annotations:jar:3.3.1.GA", "org.hibernate:hibernate-commons-annotations:jar:3.0.0.ga"]
JTA = "javax.transaction:jta:jar:1.0.1B"
CGLIB = "cglib:cglib:jar:2.1_3"
EHCACHE= "net.sf.ehcache:ehcache:jar:1.2.3"
C3P0='c3p0:c3p0:jar:0.9.1'
HIBERNATE=[HIBERNATE_SELF, JTA, EHCACHE, CGLIB, "javax.persistence:persistence-api:jar:1.0", "dom4j:dom4j:jar:1.6.1", "asm:asm-attrs:jar:1.5.3", "asm:asm:jar:1.5.3", "antlr:antlr:jar:2.7.6", "commons-logging:commons-logging:jar:1.0.4"]
ACTIVE_OBJECTS=['net.java.dev.activeobjects:activeobjects:jar:0.8.2']
CAYENNE=['org.apache.cayenne:cayenne:jar:2.0.4']

DB_VERS='1.2-SNAPSHOT'
DATABINDER_COMPONENTS="net.databinder:databinder-components:jar:#{DB_VERS}"
DATABINDER_SELF=[DATABINDER_COMPONENTS, group("databinder-app", "databinder-auth-components", "databinder-models", :under => "net.databinder", :version => DB_VERS)]
DATABINDER_DISPATCH = [HTTPCLIENT, "net.databinder:databinder-dispatch:jar:#{DB_VERS}", \
  'org.scala-lang:scala-library:jar:2.7.0']
DATABINDER_DISPATCH_COMP = [DATABINDER_DISPATCH, "net.databinder:databinder-dispatch-components:jar:#{DB_VERS}"]
DATABINDER_DRAW=[BATIK, "net.databinder:databinder-draw:jar:#{DB_VERS}"]

DATABINDER_CORE=[DATABINDER_SELF, WICKET, C3P0]

DATABINDER_HIB=[DATABINDER_CORE, HIBERNATE, group("databinder-models-hib","databinder-auth-components-hib","databinder-components-hib", "databinder-app-hib", :under => "net.databinder", :version => DB_VERS)]
DATABINDER_AO=[DATABINDER_CORE, ACTIVE_OBJECTS, group("databinder-models-ao","databinder-auth-components-ao","databinder-components-ao","databinder-app-ao", :under => "net.databinder", :version => DB_VERS)]
DATABINDER_CAY=[DATABINDER_CORE, CAYENNE, group("databinder-models-cay","databinder-components-cay","databinder-app-cay", :under => "net.databinder", :version => DB_VERS)]

JETTY = group('jetty','jetty-util','jetty-ajp', 'servlet-api-2.5', :under=>'org.mortbay.jetty', :version=>'6.1.7')
