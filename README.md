<p align="center">
  <img src="https://github.com/user-attachments/assets/ba7744d0-eba7-446a-a6ef-0be88da24980" alt="MAS Logo" width="120" height="120">
</p>

<h1 align="center">Repossify</h1>

<p align="center">
  Repossify is a Maven repository designed for the efficient management, storage, and distribution of Java artifacts. The backend is fully implemented in Java, ensuring robustness, scalability, and full compatibility with the standard Maven ecosystem.
</p>

<div align="center">
  <a href="https://repossify.dev">Website</a>
  ·
  <a href="https://repossify.dev/docs/">Official Guide</a>
  ·
  <a href="https://github.com/d4nilpzz/maven-repossify/releases">GitHub Releases</a>
  ·
  <a href="https://maven.repossify.dev">Demo</a>
  ·
  <a href="https://discord.repossify.dev">Discord</a>
</div>

---

<img width="1209" height="748" alt="{AA134C8B-A447-4E9B-8906-44B440355061}" src="https://github.com/user-attachments/assets/6e6321d3-332e-4751-9151-4fa1e5dcfa05" />


### Setup
Follow the steps below to configure and run the project.

`Prerequisites`

Before getting started, ensure you have the following installed:

 - Java Development Kit (JDK) 21

To start with the setup you have to donwload the latest version of repossify, you will find it in releases and donwload the last one.

Once you have the jar file place it in the folder you want to create the project.

To setup the files, database (SQLite) & config files execute this command.

```bash
java -jar .\repossify-1.0.0.jar --init
```

Once finished you can run the aplication with this command.

```bash
java -jar .\repossify-1.0.0.jar
```

The default configuration for running the aplication is:
 - PORT ➜ `8080`
 - HOSTNAME ➜ `127.0.0.1`

If you want to see all the params you have open the [docs](https://repossify.dev/docs/params)

---

### CHECK THE DOCS FOR MORE INFO [HERE](https://repossify.dev/docs/)
