# The war container. Built on the instance (context = /opt/memberroll, where
# push-war.sh drops server.war); the instance never compiles anything —
# the war arrives prebuilt from the dev machine.
FROM tomcat:10.1-jre17-temurin

# RemoteIpValve so request.getScheme()/isSecure() reflect X-Forwarded-Proto
# from Caddy (see tomcat/context.xml).
COPY tomcat/context.xml /usr/local/tomcat/conf/context.xml
COPY server.war /usr/local/tomcat/webapps/server.war
