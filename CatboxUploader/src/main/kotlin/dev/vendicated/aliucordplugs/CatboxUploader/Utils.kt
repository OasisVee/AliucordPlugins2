package dev.vendicated.aliucordplugs.CatboxUploader

data class Config(
    var Name: String = "Catbox",
    var DestinationType: String = "ImageUploader",
    var RequestType: String = "POST",
    var RequestURL: String = "https://catbox.moe/user/api.php",
    var FileFormName: String = "fileToUpload",
    var Headers: Map<String, String>? = null,
    var Arguments: Map<String, String> = mapOf("reqtype" to "fileupload"),
    var ResponseType: String = "URL",
    var URL: String? = null
)
