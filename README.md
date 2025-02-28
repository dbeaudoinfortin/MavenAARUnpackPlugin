# Maven AAR Unpack Plugin <img src="https://github.com/user-attachments/assets/eea7abc2-47ec-44ac-8d8a-c88cbb56af4f" height="35"/>

A Maven plugin for unpacking Android Archive Library (AAR) files. Makes your life easier when your Maven Java project needs to compile against Android system libraries but you can't or don't want to install the Android SDK locally.

## How to use

Add the following to the `plugins` section of your `pom.xml`:

```xml
<plugin>
  <groupId>io.github.dbeaudoinfortin</groupId>
  <artifactId>maven-aar-unpack-plugin</artifactId>
  <version>0.0.1</version>
  <executions>
    <execution>
      <id>aar-unpack</id>
      <phase>process-sources</phase>
      <goals><goal>aar-unpack</goal></goals>
    </execution>
  </executions>    
</plugin>
```

Then declare the Android Archive Library (AAR) dependencies as type `aar` in the `dependencies` section of your `pom.xml`:

```xml
<dependency>
  <groupId>androidx.graphics</groupId>
  <artifactId>graphics-core</artifactId>
  <version>1.0.2</version>
  <scope>provided</scope>
  <type>aar</type>
</dependency>
```

If your AAR comes from the Google Maven repository then you might need to declare it in the `repositories` section of your `pom.xml`:

```xml
<repository>
  <id>google</id>
  <url>https://maven.google.com</url>
</repository>
```

## How it works

This plugin executes in the `process-sources` lifecycle phase, prior to the compilation phase. It scans the Maven project for dependencies that are declared as type `aar` and resolves them. The resolved `.aar` files are each extracted to individual sub-directories in the `/target` directory. Each project dependency is then modified to be system scoped with a system path that references the `classes.jar` file extracted from the `.aar` file. Finally, all dependencies are forcefully re-resolved prior to compilation, adding them to the `classpath` of the Maven compiler plugin.

## Configuration Options

As an alternative to declaring AARs as project dependencies, they can be explicitly declared under the configuration property `aars`, in the Maven dependency coordinate format `<groupId>:<artifactId>[:<extension>[:<classifier>]]:<version>`. For example:

```xml
<plugin>
  <groupId>io.github.dbeaudoinfortin</groupId>
  <artifactId>maven-aar-unpack-plugin</artifactId>
  <version>0.0.1</version>
  <executions>
    <execution>
      <id>aar-unpack</id>
      <phase>process-sources</phase>
      <configuration>
        <aars>
          <aar>androidx.graphics:graphics-core:1.0.2</aar>
          <aar>androidx.graphics:graphics-shapes-android:1.0.1</aar>
        </aars>
      </configuration>
      <goals>
        <goal>aar-unpack</goal>
      </goals>
    </execution>
  </executions>
</plugin>
```
When explicitly declaring AARs, project dependencies will not be scanned. Declaring AARs using both methods may cause compilation issues.

## Legal Stuff

Copyright (c) 2025 David Fortin

This software is provided by David Fortin under the MIT License, meaning you are free to use it however you want, as long as you include the original copyright notice (above) and license notice in any copy you make. You just can't hold me liable in case something goes wrong. License details can be read [here](https://github.com/dbeaudoinfortin/MavenAARUnpackPlugin?tab=MIT-1-ov-file)
