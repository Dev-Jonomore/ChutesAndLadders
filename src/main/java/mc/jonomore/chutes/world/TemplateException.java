package mc.jonomore.chutes.world;

/** A template could not be saved, loaded or deleted; the message is meant for the template author. */
public final class TemplateException extends Exception {

    public TemplateException(String message) {
        super(message);
    }
}
